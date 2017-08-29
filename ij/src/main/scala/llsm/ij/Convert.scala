package llsm.ij

import java.awt.Frame
import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.stream.Collectors

import scala.collection.JavaConverters._
import scala.util.{Try, Success, Failure}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import cats.MonadError
import cats.data.Coproduct
import cats.implicits._
import io.scif.img.cell.SCIFIOCellImgFactory
import ij.gui.YesNoCancelDialog
import llsm.{
  NoInterpolation,
  NNInterpolation,
  LinearInterpolation,
  LanczosInterpolation,
  Programs,
  ImgUtils
}
import llsm.algebras.{
  Metadata,
  MetadataF,
  ImgReader,
  ImgReaderF,
  ImgWriter,
  ImgWriterF,
  Process,
  ProcessF,
  Progress,
  ProgressF
}
import llsm.fp.ParSeq
import llsm.fp.ParSeq.ops._
import llsm.interpreters._
import llsm.io.metadata.{ ConfigurableMetadata }
import llsm.ij.interpreters._
import net.imglib2.img.ImgFactory
import net.imglib2.img.array.ArrayImgFactory
import net.imglib2.img.planar.PlanarImgFactory
import net.imglib2.`type`.numeric.integer.UnsignedShortType
import org.scijava.{Context, ItemIO}
import org.scijava.app.StatusService
import org.scijava.command.Command
import org.scijava.log.LogService
import org.scijava.ui.UIService
import org.scijava.plugin.{Plugin, Parameter}


@Plugin(`type` = classOf[Command],
        headless = true,
        menuPath = "Plugins>LLSM>Convert Dataset...")
class ConvertPlugin extends Command {

  @Parameter(style = "directory", `type` = ItemIO.INPUT)
  var input: File = _

  @Parameter(style = "directory", label = "Output Directory", `type` = ItemIO.INPUT)
  var output: File = _

  @Parameter(validater = "validateName")
  var outputFileName: String = _

  var validName: Boolean = false

  @Parameter(label = "X/Y voxel size (um)", required = true)
  var pixelSize: Double = 0.104

  @Parameter(label = "Img Container Type",
    choices = Array("Array", "Planar", "Cell"),
    persist = false,
    required = false)
  var container: String = "Cell"

  @Parameter(label = "Interpolation scheme",
             choices = Array("None", "Nearest Neighbour", "Linear", "Lanczos"),
             persist = false,
             required = false)
  var interpolation: String = "None"

  @Parameter(label = "Parallel processing (experimental)",
             persist = false)
  var parallel: Boolean = false

  @Parameter
  var ui: UIService = _

  @Parameter
  var log: LogService = _

  @Parameter
  var status: StatusService = _

  @Parameter
  var context: Context = _

  sealed trait ValidConfig
  case object ConfigSuccess extends ValidConfig
  case class ConfigFailure(msg: String) extends ValidConfig

  object ValidConfig {
    def success(): ValidConfig = ConfigSuccess
    def fail(msg: String): ValidConfig = ConfigFailure(msg)
  }

  def validateConfig(): ValidConfig =
    if (!outputFileName.endsWith(".h5") && !outputFileName.endsWith(".ome.tif"))
      ValidConfig.fail("Invalid output type. Only .h5 and .ome.tif are supported")
    else if (WriterUtils.outputExists(Paths.get(output.getPath, outputFileName))) {
      val c = new YesNoCancelDialog(
        new Frame,
        "Output file/files exist",
        "The output file/files you specified already exist. Do you want to continue and overwrite them?"
      )
      val cancel = c.cancelPressed
      val proceed = c.yesPressed
      if (cancel)
        ValidConfig.fail("Outputs exist. Action cancelled.")
      else if(!proceed)
        ValidConfig.fail("Outputs exist. User specified not to proceed.")
      else ValidConfig.success
    }
    else ValidConfig.success

  def program[F[_]: Metadata: ImgReader: ImgWriter: Process: Progress](
    paths: List[Path],
    outputPath: Path,
    context: Context
  ): ParSeq[F, Either[Throwable, Unit]] =
    Programs.convertImgsP[F](paths, outputPath)
      .map(lImg => ImgUtils.writeOMEMetadata[Either[Throwable, ?]](outputPath, lImg, context))
  /**
    * Entry point to running a plugin.
    */
  override def run(): Unit = validateConfig match {
    case ConfigSuccess => {
      type App[A] =
        Coproduct[ImgWriterF,
          Coproduct[ProcessF,
            Coproduct[ImgReaderF,
              Coproduct[MetadataF, ProgressF, ?],
            ?],
          ?],
        A]

      val config = ConfigurableMetadata(
        pixelSize,
        pixelSize,
        interpolation match {
          case "Nearest Neighbour"  => NNInterpolation
          case "Linear"             => LinearInterpolation
          case "Lancsoz"            => LanczosInterpolation
          case "None"               => NoInterpolation
          case _                    => throw new Exception("Unknown Interpolation type. Please submit a bug report.")
        })

      val imgFactory: ImgFactory[UnsignedShortType] = container match {
        case "Array"  => new ArrayImgFactory[UnsignedShortType]
        case "Planar" => new PlanarImgFactory[UnsignedShortType]
        case "Cell"   => new SCIFIOCellImgFactory[UnsignedShortType]
        case _        => throw new Exception("Unknown Img container type. Please submit a bug report.")
      }

      // Create a compiler for our program that combines interpreters for
      // reading metadata, reading data, deskewing, writing processed data and
      // reporting progress to ImageJ.
      def compiler[M[_]: MonadError[?[_], Throwable]] =
                  llsmWriter[M](context) or
                      (processCompiler[M] or
                        (ijImgReader[M](context, imgFactory, log) or
                          (ijMetadataReader[M](config, log) or
                            ijProgress[M](status))))

      // Get a list of TIFF images from the input path.
      val imgPaths = Files.list(Paths.get(input.getPath))
        .collect(Collectors.toList[Path])
        .asScala.filter(_.toString.endsWith(".tif"))

      // Create a program for processing our images. This will be executed via
      // the compiler.
      val p = program[App](imgPaths.toList, Paths.get(output.getPath, outputFileName), context)

      val outputF: Try[Either[Throwable, Unit]] => Unit = result => result match {
        case Success(Right(_)) => {
          log.info("Successfully converted images")
        }
        case Success(Left(e)) => log.error(e)
        case Failure(e) => log.error(e)
      }

      // If user specifies parallel then run program with Future effects type,
      // otherwise use Try
      if (parallel) {
        p.run(compiler[Future]) onComplete outputF
      } else {
        outputF(p.run(compiler[Try]))
      }
    }
    case ConfigFailure(m) => log.error(m)
  }
}

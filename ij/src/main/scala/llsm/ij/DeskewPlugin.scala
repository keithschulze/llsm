package llsm
package ij

import llsm.Deskew
import java.lang.Math
import net.imagej.Dataset
import net.imagej.axis.{Axes, CalibratedAxis, DefaultLinearAxis}
import net.imagej.DatasetService
import net.imglib2.img.Img
import net.imglib2.`type`.numeric.RealType
import org.scijava.ItemIO
import org.scijava.command.Command
import org.scijava.plugin.{Plugin, Parameter}

@Plugin(`type` = classOf[Command], headless = true, menuPath = "Plugins>LLSM>Deskew Image")
class DeskewPlugin extends Command {

  @Parameter(`type` = ItemIO.INPUT)
  var input: Dataset = _

  @Parameter(label = "X/Y voxel size (um)", required = true)
  var pixelSize: Double = 0.1018

  @Parameter(label = "Sample piezo increment", required = true)
  var sampleIncrement: Double = 0.3000

  @Parameter
  var ds: DatasetService = _

  @Parameter(`type` = ItemIO.OUTPUT)
  var output: Dataset = _

  /**
  * Entry point to running a plugin.
  */
  override def run(): Unit = {
    val ip: Img[T] forSome {type T <: RealType[T]} = input.getImgPlus.getImg.asInstanceOf[Img[T] forSome {type T <: RealType[T]}]

    val shearFactor: Int = Math.round(sampleIncrement / pixelSize).toInt

    val zInterval: Double = Math.sin(Math.toRadians(31.8)) * sampleIncrement

    var axes: Array[CalibratedAxis] = Array.ofDim[CalibratedAxis](input.numDimensions)

    input.axes(axes)

    axes = axes.map(a => {
      val axisType = a.`type`()
      if (axisType == Axes.X)
        new DefaultLinearAxis(Axes.X, "um", pixelSize)
      else if (axisType == Axes.Y)
        new DefaultLinearAxis(Axes.Y, "um", pixelSize)
      else if (axisType == Axes.Z)
        new DefaultLinearAxis(Axes.Z, "um", zInterval)
      else a
    })

    val xIndex = input.dimensionIndex(Axes.X)
    val zIndex = input.dimensionIndex(Axes.Z)

    output = ds.create(Deskew.deskewStack(ip, xIndex, zIndex, shearFactor))
    output.setAxes(axes)
  }
}

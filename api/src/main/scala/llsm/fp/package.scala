package llsm

import cats._
import cats.arrow.FunctionK
import cats.free.{Free, FreeApplicative}

package object fp {
  type Interpreter[F[_], G[_]]            = F ~> Free[G, ?]
  type ~<[F[_], G[_]]                     = Interpreter[F, G]
  type ApplicativeInterpreter[F[_], G[_]] = F ~> FreeApplicative[G, ?]
  type ≈<[F[_], G[_]]                     = ApplicativeInterpreter[F, G]

  type ParInterpreter[F[_], G[_]] = FreeApplicative[F, ?] ~> G
  type ParOptimiser[F[_], G[_]]   = ParInterpreter[F, SeqPar[G, ?]]
  type Halt[F[_], A]              = F[Unit]

  type ParSeq[F[_], A] = FreeApplicative[Free[F, ?], A]

  object ParSeq {

    def lift[F[_], A](fa: F[A]): ParSeq[F, A] =
      FreeApplicative.lift[Free[F, ?], A](Free.liftF(fa))

    def liftSeq[F[_], A](free: Free[F, A]): ParSeq[F, A] =
      FreeApplicative.lift[Free[F, ?], A](free)

    def liftPar[F[_], A](freeap: FreeApplicative[F, A]): ParSeq[F, A] =
      freeap.compile[Free[F, ?]](λ[(F ~> Free[F, ?])](fa => Free.liftF(fa)))

    def run[F[_], M[_], A](fa: ParSeq[F, A])(f: FunctionK[F, M])(
        implicit M: Monad[M]): M[A] =
      fa.foldMap(λ[FunctionK[Free[F, ?], M]](fb => fb.foldMap(f)))

    final class ParSeqOps[F[_], A](parseq: ParSeq[F, A]) {
      def run[M[_]: Monad](f: FunctionK[F, M]): M[A] = ParSeq.run(parseq)(f)
    }

    object ops {
      implicit def parseqSyntax[F[_], A](ps: ParSeq[F, A]): ParSeqOps[F, A] =
        new ParSeqOps[F, A](ps)
    }
  }

  type SeqPar[F[_], A] = Free[FreeApplicative[F, ?], A]

  object SeqPar {
    def lift[F[_], A](fa: F[A]): SeqPar[F, A] =
      Free.liftF[FreeApplicative[F, ?], A](FreeApplicative.lift(fa))

    def liftSeq[F[_], A](free: Free[F, A]): SeqPar[F, A] =
      free.compile[FreeApplicative[F, ?]](λ[(F ~> FreeApplicative[F, ?])](fa =>
        FreeApplicative.lift(fa)))

    def liftPar[F[_], A](freeap: FreeApplicative[F, A]): SeqPar[F, A] =
      Free.liftF[FreeApplicative[F, ?], A](freeap)

    def run[F[_], M[_], A](fa: SeqPar[F, A])(f: FunctionK[F, M])(
        implicit M: Monad[M]): M[A] =
      fa.foldMap(λ[FunctionK[FreeApplicative[F, ?], M]](fa => fa.foldMap(f)))

    final class SeqParOps[F[_], A](sps: SeqPar[F, A]) {
      def run[M[_]: Monad](f: FunctionK[F, M]): M[A] = SeqPar.run(sps)(f)
    }

    object ops {
      implicit def spsSyntax[F[_], A](sp: SeqPar[F, A]): SeqParOps[F, A] =
        new SeqParOps[F, A](sp)
    }
  }

  implicit def haltFunctor[F[_]]: Functor[Halt[F, ?]] =
    new Functor[Halt[F, ?]] {
      override def map[A, B](fa: Halt[F, A])(f: (A) => B): Halt[F, B] = fa
    }

  implicit class FreeHaltOps[F[_], A](free: Free[Halt[F, ?], A]) {
    def unhalt: Free[F, Unit] =
      free.fold(x => Free.pure(()), Free.liftF(_))
  }
}

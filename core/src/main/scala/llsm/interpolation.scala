package llsm

sealed trait InterpolationMethod
case object NoInterpolation      extends InterpolationMethod
case object NNInterpolation      extends InterpolationMethod
case object LinearInterpolation  extends InterpolationMethod
case object LanczosInterpolation extends InterpolationMethod

package dev.gamepad.sdk

open class GamepadException(message: String, cause: Throwable? = null) : Exception(message, cause)
class LinkClosedException(message: String = "Connection closed", cause: Throwable? = null) :
    GamepadException(message, cause)
class ProtocolException(message: String, cause: Throwable? = null) : GamepadException(message, cause)
class BufferOverflowException(message: String) : GamepadException(message)
class NotReadyException : GamepadException("Controller is not ready")
class SdkClosedException : GamepadException("SDK/controller has been closed")
class VerificationException(message: String) : GamepadException(message)

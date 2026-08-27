package storage

import java.nio.file.Path

/** A storage failure that callers can handle without terminating the process. */
sealed trait StoreError extends Product with Serializable {
  def message: String
  def cause: Option[Throwable]
}

object StoreError {
  final case class OpenFailed(path: Path, error: Throwable) extends StoreError {
    override val message: String = s"Could not open the state store at $path: ${error.getMessage}"
    override val cause: Option[Throwable] = Some(error)
  }

  final case class ReadFailed(path: Path, operation: String, error: Throwable) extends StoreError {
    override val message: String = s"State-store $operation failed at $path: ${error.getMessage}"
    override val cause: Option[Throwable] = Some(error)
  }

  final case class WriteFailed(path: Path, error: Throwable) extends StoreError {
    override val message: String = s"State-store write failed at $path: ${error.getMessage}"
    override val cause: Option[Throwable] = Some(error)
  }

  final case class CloseFailed(path: Path, error: Throwable) extends StoreError {
    override val message: String = s"Could not close the state store at $path: ${error.getMessage}"
    override val cause: Option[Throwable] = Some(error)
  }

  final case class InvalidArgument(message: String) extends StoreError {
    override val cause: Option[Throwable] = None
  }

  final case class Closed(path: Path) extends StoreError {
    override val message: String = s"The state store at $path is closed"
    override val cause: Option[Throwable] = None
  }
}

/** Compatibility exception for existing synchronous database APIs. */
final class StoreException(val error: StoreError)
  extends RuntimeException(error.message, error.cause.orNull)


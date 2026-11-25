package com.pivot.banking.account.domain.exception;

/** Signals a movement-service failure and whether its result may be ambiguous. */
public class DownstreamServiceException extends RuntimeException {

  private final boolean ambiguous;

  /** Creates an exception for a confirmed or ambiguous downstream failure. */
  public DownstreamServiceException(String message, boolean ambiguous, Throwable cause) {
    super(message, cause);
    this.ambiguous = ambiguous;
  }

  /** Returns whether the remote operation may have succeeded despite the failure. */
  public boolean isAmbiguous() {
    return ambiguous;
  }
}

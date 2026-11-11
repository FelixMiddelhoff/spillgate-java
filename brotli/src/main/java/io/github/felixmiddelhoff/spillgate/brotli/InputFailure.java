package io.github.felixmiddelhoff.spillgate.brotli;

import java.io.IOException;

/** Carries an exception of the {@code CodecInput} (source failure, limit) through the decoder. */
final class InputFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  InputFailure(IOException cause) {
    super(cause);
  }

  IOException io() {
    return (IOException) getCause();
  }
}

/* Copyright 2015 Google Inc. All Rights Reserved.

   Distributed under MIT license.
   See file LICENSE for detail or copy at https://opensource.org/licenses/MIT
*/

package io.github.felixmiddelhoff.spillgate.brotli;

/**
 * Unchecked exception used internally.
 */
class BrotliRuntimeException extends RuntimeException {
  private static final long serialVersionUID = 1L;


  BrotliRuntimeException(String message) {
    super(message);
  }

  BrotliRuntimeException(String message, Throwable cause) {
    super(message, cause);
  }
}

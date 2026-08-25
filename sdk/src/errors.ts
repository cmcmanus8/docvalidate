import type { ProblemDetail, Validation } from './types.js';

/**
 * Every failure the SDK raises is one of these, so a caller can catch the base class and
 * still branch on `code`. The code comes from the service's problem body rather than
 * from the status line, because 409 alone means three different things here.
 */
export class DocValidateError extends Error {
  readonly status: number | undefined;
  readonly code: string | undefined;
  readonly problem: ProblemDetail | undefined;
  readonly requestId: string | undefined;

  constructor(message: string, options: { status?: number; problem?: ProblemDetail; cause?: unknown } = {}) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = new.target.name;
    this.status = options.status;
    this.problem = options.problem;
    this.code = options.problem?.code;
    this.requestId = options.problem?.requestId;
  }
}

/** No request with that id. */
export class ValidationNotFoundError extends DocValidateError {}

/** The request already holds different bytes: documents are immutable once accepted. */
export class ContentMismatchError extends DocValidateError {}

/** The bytes are not the kind of document the request said it would carry. */
export class DeclaredTypeMismatchError extends DocValidateError {}

/** The upload window closed before the bytes arrived. */
export class RequestExpiredError extends DocValidateError {}

/** The request is in a status that does not allow what was attempted. */
export class InvalidStateTransitionError extends DocValidateError {}

/** The request was malformed, or the document was larger than the service accepts. */
export class InvalidRequestError extends DocValidateError {}

/** The service failed, and the failure is not the caller's to fix. */
export class ServiceError extends DocValidateError {}

/** The request never got an answer: DNS, connection, timeout, abort. */
export class NetworkError extends DocValidateError {}

/** Polling gave up before the request reached a terminal status. */
export class ValidationTimeoutError extends DocValidateError {
  readonly lastKnown: Validation;

  constructor(message: string, lastKnown: Validation) {
    super(message);
    this.lastKnown = lastKnown;
  }
}

const BY_CODE: Record<string, new (message: string, options?: { status?: number; problem?: ProblemDetail }) => DocValidateError> = {
  VALIDATION_NOT_FOUND: ValidationNotFoundError,
  CONTENT_MISMATCH: ContentMismatchError,
  DECLARED_TYPE_MISMATCH: DeclaredTypeMismatchError,
  REQUEST_EXPIRED: RequestExpiredError,
  INVALID_STATE_TRANSITION: InvalidStateTransitionError,
  INVALID_REQUEST: InvalidRequestError,
  PAYLOAD_TOO_LARGE: InvalidRequestError,
  LENGTH_REQUIRED: InvalidRequestError,
  RESOURCE_NOT_FOUND: ValidationNotFoundError,
  METHOD_NOT_ALLOWED: InvalidRequestError,
  STORAGE_FAILURE: ServiceError,
  INTERNAL_ERROR: ServiceError,
};

export function errorFor(status: number, problem: ProblemDetail | undefined): DocValidateError {
  const code = problem?.code;
  const message = problem?.detail ?? problem?.title ?? `Request failed with status ${status}`;

  const ByCode = code === undefined ? undefined : BY_CODE[code];
  if (ByCode !== undefined) {
    return new ByCode(message, { status, ...(problem === undefined ? {} : { problem }) });
  }

  // An unrecognised code still has to land somewhere sensible: a proxy or a future
  // version of the service can return a shape this SDK has never heard of.
  const Fallback = status >= 500 ? ServiceError : InvalidRequestError;
  return new Fallback(message, { status, ...(problem === undefined ? {} : { problem }) });
}

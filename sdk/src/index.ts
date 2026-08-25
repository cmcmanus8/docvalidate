export { DocValidateClient, createClient } from './client.js';
export type {
  CreateValidationInput,
  DocValidateClientOptions,
  DocumentInput,
  WaitOptions,
} from './client.js';
export type { FetchLike, RetryOptions } from './http.js';
export {
  ContentMismatchError,
  DeclaredTypeMismatchError,
  DocValidateError,
  InvalidRequestError,
  InvalidStateTransitionError,
  NetworkError,
  RequestExpiredError,
  ServiceError,
  UnknownEndpointError,
  ValidationNotFoundError,
  ValidationTimeoutError,
} from './errors.js';
export { TERMINAL_STATUSES, isTerminal } from './types.js';
export type {
  CreateValidationResponse,
  DocumentSummary,
  ProblemCode,
  ProblemDetail,
  Validation,
  ValidationResultSummary,
  ValidationStatus,
  Verdict,
} from './types.js';

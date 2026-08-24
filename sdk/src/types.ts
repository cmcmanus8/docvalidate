/** Mirrors the service's status machine. Terminal states are COMPLETED, FAILED and EXPIRED. */
export type ValidationStatus =
  | 'PENDING_UPLOAD'
  | 'QUEUED'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'FAILED'
  | 'EXPIRED';

/** ERROR means the document was never judged, not that it was rejected. */
export type Verdict = 'VALID' | 'INVALID' | 'ERROR';

export interface CreateValidationResponse {
  requestId: string;
  uploadUrl: string;
  status: ValidationStatus;
  expiresAt: string;
}

export interface DocumentSummary {
  filename: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
}

export interface ValidationResultSummary {
  verdict: Verdict;
  reason?: string;
  extractedFields?: Record<string, unknown>;
}

export interface Validation {
  requestId: string;
  status: ValidationStatus;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  document?: DocumentSummary;
  result?: ValidationResultSummary;
}

/** The stable codes the service puts on every problem response. */
export type ProblemCode =
  | 'VALIDATION_NOT_FOUND'
  | 'INVALID_STATE_TRANSITION'
  | 'CONTENT_MISMATCH'
  | 'REQUEST_EXPIRED'
  | 'PAYLOAD_TOO_LARGE'
  | 'LENGTH_REQUIRED'
  | 'INVALID_REQUEST'
  | 'RESOURCE_NOT_FOUND'
  | 'METHOD_NOT_ALLOWED'
  | 'STORAGE_FAILURE'
  | 'INTERNAL_ERROR';

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: ProblemCode | string;
  requestId?: string;
}

export const TERMINAL_STATUSES: readonly ValidationStatus[] = ['COMPLETED', 'FAILED', 'EXPIRED'];

export function isTerminal(status: ValidationStatus): boolean {
  return TERMINAL_STATUSES.includes(status);
}

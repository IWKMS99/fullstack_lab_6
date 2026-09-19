interface ApiErrorPayload {
  message?: string;
  violations?: Array<{
    field?: string;
    description?: string;
  }>;
}

interface ApiErrorLike {
  response?: {
    status?: number;
    data?: ApiErrorPayload;
  };
}

export const getApiStatus = (error: unknown): number | undefined => {
  const typedError = error as ApiErrorLike | null | undefined;
  return typedError?.response?.status;
};

export const getApiErrorMessage = (error: unknown, fallback: string): string => {
  const typedError = error as ApiErrorLike | null | undefined;
  return typedError?.response?.data?.message ?? fallback;
};

export const getValidationViolationMessage = (
  error: unknown,
  fields: string[]
): string | undefined => {
  const typedError = error as ApiErrorLike | null | undefined;
  const violations = typedError?.response?.data?.violations ?? [];
  if (!Array.isArray(violations)) {
    return undefined;
  }

  const targetFieldSet = new Set(fields);
  const match = violations.find(
    (violation) =>
      typeof violation?.field === 'string' &&
      typeof violation?.description === 'string' &&
      targetFieldSet.has(violation.field)
  );

  return match?.description;
};

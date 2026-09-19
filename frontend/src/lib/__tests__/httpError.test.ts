import {expect, it} from 'vitest';
import {getApiErrorMessage, getApiStatus, getValidationViolationMessage} from '../httpError';

it('handles missing response, null and unknown thrown values', () => {
  for (const error of [null, undefined, 'network offline', new Error('connection reset'), {}]) {
    expect(getApiErrorMessage(error, 'Retry later')).toBe('Retry later');
    expect(getApiStatus(error)).toBeUndefined();
    expect(getValidationViolationMessage(error, ['email'])).toBeUndefined();
  }
});

it('preserves API status and actionable validation messages', () => {
  const error = {response: {status: 409, data: {message: 'Conflict', violations: [null, {field: 'capacity', description: 'Positive capacity required'}, {field: 'name', description: 4}]}}};
  expect(getApiStatus(error)).toBe(409);
  expect(getApiErrorMessage(error, 'Failure')).toBe('Conflict');
  expect(getValidationViolationMessage(error, ['capacity'])).toBe('Positive capacity required');
  expect(getValidationViolationMessage(error, ['name'])).toBeUndefined();
  expect(getValidationViolationMessage({response: {data: {violations: 'malformed'}}}, ['name'])).toBeUndefined();
});

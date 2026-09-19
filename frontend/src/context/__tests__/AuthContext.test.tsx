import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {AuthProvider} from '../AuthContext';
import {useAuth} from '../useAuth';
import {getCurrentUser, logoutUser, refreshAccessToken, setupInterceptors} from '../../services/api';

vi.mock('../../services/api', () => ({getCurrentUser: vi.fn(), logoutUser: vi.fn(), refreshAccessToken: vi.fn(), setupInterceptors: vi.fn()}));
const token = (roles = ['ROLE_USER'], exp = Math.floor(Date.now() / 1000) + 3600) => `${btoa('{}')}.${btoa(JSON.stringify({sub: 'member@example.test', roles, exp}))}.signature`;
const viewer = {id: 'u1', email: 'member@example.test', roles: ['ROLE_USER']};
let nextLoginToken = '';

function Probe() {
  const auth = useAuth();
  return <><output>{auth.isLoading ? 'loading' : auth.isAuthenticated ? `signed:${auth.isAdmin ? 'admin' : 'user'}` : 'guest'}</output><button onClick={() => void auth.logout()}>logout</button><button onClick={() => void auth.login(nextLoginToken).catch(() => {})}>login</button></>;
}

beforeEach(() => {
  vi.resetAllMocks(); localStorage.clear();
  vi.mocked(setupInterceptors).mockReturnValue(vi.fn());
  vi.mocked(refreshAccessToken).mockRejectedValue(new Error('No session'));
  vi.mocked(getCurrentUser).mockResolvedValue(viewer);
  nextLoginToken = token();
});
afterEach(cleanup);

it('bootstraps a valid stored token and resolves roles from current user', async () => {
  localStorage.setItem('authToken', token(['ROLE_ADMIN']));
  vi.mocked(getCurrentUser).mockResolvedValue({...viewer, roles: ['ROLE_ADMIN']});
  render(<AuthProvider><Probe /></AuthProvider>);
  expect(await screen.findByText('signed:admin')).toBeInTheDocument();
  expect(refreshAccessToken).not.toHaveBeenCalled();
});

it('refreshes an expired token and restores a server-backed session', async () => {
  localStorage.setItem('authToken', token([], 1));
  const fresh = token();
  vi.mocked(refreshAccessToken).mockResolvedValue({token: fresh});
  render(<AuthProvider><Probe /></AuthProvider>);
  expect(await screen.findByText('signed:user')).toBeInTheDocument();
  expect(localStorage.getItem('authToken')).toBe(fresh);
  expect(refreshAccessToken).toHaveBeenCalledOnce();
});

it('clears an invalid token when refresh fails', async () => {
  localStorage.setItem('authToken', 'invalid');
  render(<AuthProvider><Probe /></AuthProvider>);
  expect(await screen.findByText('guest')).toBeInTheDocument();
  expect(localStorage.getItem('authToken')).toBeNull();
});

it('cleans local credentials even when logout endpoint is unavailable', async () => {
  localStorage.setItem('authToken', token());
  vi.mocked(logoutUser).mockRejectedValue(new Error('offline'));
  render(<AuthProvider><Probe /></AuthProvider>);
  await screen.findByText('signed:user');
  fireEvent.click(screen.getByText('logout'));
  expect(await screen.findByText('guest')).toBeInTheDocument();
  expect(localStorage.getItem('authToken')).toBeNull();
});

it('handles current-user failure and interceptor session expiry', async () => {
  localStorage.setItem('authToken', token());
  vi.mocked(getCurrentUser).mockRejectedValueOnce(new Error('expired'));
  vi.mocked(refreshAccessToken).mockResolvedValue({token: token()});
  render(<AuthProvider><Probe /></AuthProvider>);
  await screen.findByText('signed:user');
  const options = vi.mocked(setupInterceptors).mock.calls[0][0];
  expect(options.getAccessToken()).not.toBeNull();
  act(() => options.setAccessToken(token(['ROLE_ADMIN'])));
  act(() => options.onUnauthorized());
  expect(screen.getByText('guest')).toBeInTheDocument();
});

it('loads login identity, with fallback only for a decodable token', async () => {
  render(<AuthProvider><Probe /></AuthProvider>);
  await screen.findByText('guest');
  fireEvent.click(screen.getByText('login'));
  await screen.findByText('signed:user');
  fireEvent.click(screen.getByText('logout'));
  await screen.findByText('guest');
  vi.mocked(getCurrentUser).mockRejectedValue(new Error('offline'));
  nextLoginToken = token(['ROLE_ADMIN']);
  fireEvent.click(screen.getByText('login'));
  await screen.findByText('signed:admin');
  fireEvent.click(screen.getByText('logout'));
  await screen.findByText('guest');
  nextLoginToken = 'bad-token';
  fireEvent.click(screen.getByText('login'));
  await waitFor(() => expect(localStorage.getItem('authToken')).toBeNull());
});

it('fails closed when refresh succeeds but identity fetch fails', async () => {
  vi.mocked(refreshAccessToken).mockResolvedValue({token: token()});
  vi.mocked(getCurrentUser).mockRejectedValue(new Error('not found'));
  const view = render(<AuthProvider><Probe /></AuthProvider>);
  await screen.findByText('guest');
  const teardown = vi.mocked(setupInterceptors).mock.results[0].value;
  view.unmount();
  expect(teardown).toHaveBeenCalledOnce();
});

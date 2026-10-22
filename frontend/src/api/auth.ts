import { request } from './client'
import type { LoginResponse, User } from './types'

/**
 * The sender facing authentication endpoints.
 */

/** `POST /auth/login` - starts a session and returns the CSRF token. */
export async function login(email: string, password: string): Promise<LoginResponse> {
  return request<LoginResponse>('/auth/login', {
    method: 'POST',
    body: { email, password },
    auth: false,
  })
}

/** `POST /auth/logout` - ends the session. */
export async function logout(): Promise<void> {
  await request<void>('/auth/logout', { method: 'POST' })
}

/** `GET /auth/me` - the sender of the current session. */
export async function me(): Promise<User> {
  return request<User>('/auth/me')
}

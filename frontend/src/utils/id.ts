/**
 * A collision free id generator.
 *
 * The editor needs stable ids for the recipients and the fields it keeps in
 * memory before they are stored by the backend. `crypto.randomUUID` is used
 * when it is available, otherwise a counter is combined with the current time.
 */

let counter = 0

/** Returns a new unique id. */
export function newId(): string {
  const cryptoApi = globalThis.crypto
  if (cryptoApi && typeof cryptoApi.randomUUID === 'function') {
    return cryptoApi.randomUUID()
  }
  counter += 1
  return `id-${Date.now().toString(36)}-${counter.toString(36)}`
}

/**
 * Which model to ask. Gemini's big Flash models are sometimes "experiencing high demand": they
 * answer 503, or hang without a word. A call can't wait for that, so the configured model gets
 * a few seconds, and when it's busy or slow the fallbacks (Flash-Lites, quick and less often
 * busy) answer instead, in turn, for a couple of minutes before the first is tried again.
 */
export class ModelChoice {
  readonly primary: string
  readonly fallbacks: string[]
  private readonly now: () => number
  private readonly restMs: number
  private downUntil = 0

  /** [fallbacks]: one model id, or several separated by commas, tried in that order. */
  constructor(primary: string, fallbacks: string, now: () => number = Date.now, restMs = 2 * 60_000) {
    this.primary = primary
    this.fallbacks = [...new Set(fallbacks.split(',').map(m => m.trim()).filter(m => m && m !== primary))]
    this.now = now
    this.restMs = restMs
  }

  /** The models to try, in order. */
  order(): string[] {
    return this.now() < this.downUntil ? this.fallbacks : [this.primary, ...this.fallbacks]
  }

  /** [model] was busy or slow: if it's the configured one, the fallbacks answer for a while. */
  busy(model: string) {
    if (model === this.primary && this.fallbacks.length) {
      if (this.now() >= this.downUntil) console.warn(`[model] ${this.primary} is busy or slow; ${this.fallbacks.join(', then ')} answer for the next ${Math.round(this.restMs / 60_000)} minutes`)
      this.downUntil = this.now() + this.restMs
    }
  }
}

/** Too long without an answer: treated like a 503. */
export class SlowModelError extends Error {
  constructor(model: string, ms: number) {
    super(`${model} said nothing in ${Math.round(ms / 1000)} s`)
  }
}

/** Worth asking another model: busy, rate-limited, failing, slow or unreachable, not a bad request. */
export function isBusy(e: unknown): boolean {
  if (e instanceof SlowModelError) return true
  const status = (e as { status?: unknown })?.status
  return typeof status !== 'number' || status === 408 || status === 429 || status >= 500
}

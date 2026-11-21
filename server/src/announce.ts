import type { AgentApi } from './agora.ts'
import type { Bridge } from './bridge.ts'
import type { Hub } from './hub.ts'
import type { Sessions } from './sessions.ts'

/** Records something the elder should hear about and, if she's on a call, tells her now. */
export type Announce = (kind: string, text: string, item?: unknown) => Promise<void>

/**
 * [text] is written to be said to her ("Rahul said yes: take Amma to Dr. Rao's clinic"). During
 * a call the agent says it after what it's saying (Agora /speak), the call screen shows it, and
 * the model is told in the next turn's prompt; otherwise the phone tells her (GET /family/answers)
 * and her next call opens with it.
 */
export function announcer(deps: { hub: Hub; sessions: Sessions; bridge: Bridge; agents: AgentApi; clock: () => Date }): Announce {
  return async (kind, text, item) => {
    const { hub, sessions, bridge, agents, clock } = deps
    const event = hub.addEvent(kind, text, item ?? null, clock().getTime())
    const live = sessions.activeFor(hub.elderId)
    if (!live || !bridge.connected(live.id)) return
    hub.markTold(event.id)
    live.actions.push(`Update told to her: ${text}`)
    bridge.push(live.id, { type: 'hub_update', kind, text, item: item ?? null })
    if (live.agentId) {
      try {
        await agents.speak(live.agentId, text)
      } catch (e) {
        console.warn(`[announce] Agora /speak failed: ${(e as Error).message}`)
      }
    }
  }
}

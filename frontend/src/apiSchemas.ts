import { z } from 'zod'

const text = z.string()
const number = z.number()
const flag = z.boolean()
const nullableText = text.nullable()
const id = z.object({ id: text })
const named = id.extend({ name: text })
const user = id.extend({
  username: text.optional(),
  displayName: text,
  role: z.enum(['ADMIN', 'USER']),
  enabled: flag.optional(),
  mustChangePassword: flag,
})
const conversation = id.extend({ title: text, updatedAt: text, projectId: nullableText.optional() })
const project = named.extend({ instructions: text.optional() })
const agent = named.extend({ instructions: text, modelId: text })
const material = named.extend({ byteSize: number })
const publishedVersion = z.object({ versionId: text, title: text, versionNumber: number })
const version = publishedVersion.extend({
  id: text,
  taskId: text,
  byteSize: number,
  baseVersionId: nullableText.optional(),
})
const task = id.extend({
  requestId: text,
  outputMode: text.optional(),
  status: text,
  executionState: text,
  agentName: nullableText.optional(),
  errorCode: nullableText.optional(),
  publishedVersion: publishedVersion.nullable().optional(),
})
const message = z.object({
  id: number,
  taskId: text,
  role: text,
  content: text,
  publishedVersion: publishedVersion.nullable().optional(),
})
const budget = z.object({
  currency: text,
  limit: text,
  spent: text,
  reserved: text,
  available: text,
})
const settings = z.object({ currency: text, locked: flag })
const model = named.extend({
  inputPrice: text.optional(),
  outputPrice: text.optional(),
})
const adminModel = model.extend({
  baseUrl: text,
  modelName: text,
  credentialRef: text,
  enabled: flag,
  contextWindow: number.nullable().optional(),
})
const parse = named.extend({
  status: text,
  cleanup: text,
  characters: number.nullable(),
  errorCode: nullableText,
})
const call = id.extend({
  purpose: text,
  status: text,
  source: text,
  inputTokens: number.nullable(),
  outputTokens: number.nullable(),
  charged: text,
  reservation: text,
  currency: text,
  startedAt: text,
  inputEstimate: number,
  observedOutputBytes: number,
  providerResponseId: nullableText,
})
const diagnosticTask = id.extend({
  ownerId: text,
  conversationId: text,
  modelId: text,
  status: text,
  executionState: text,
  errorCode: nullableText,
  createdAt: text,
  updatedAt: text,
  contextState: text,
  contextAt: nullableText,
  summaryState: text,
  summaryAt: nullableText,
})
const diagnostic = z.object({
  task: diagnosticTask,
  calls: z.array(
    call.omit({ inputEstimate: true, observedOutputBytes: true, providerResponseId: true }).extend({
      errorCode: nullableText,
      providerState: text,
      sentAt: nullableText,
      usageAt: nullableText,
      endedAt: nullableText,
    })
  ),
  parses: z.array(
    id.extend({
      sandboxJobId: text,
      materialId: text,
      status: text,
      cleanup: text,
      errorCode: nullableText,
      createdAt: text,
      updatedAt: text,
      cleanupAttempts: number,
      cleanupAttemptedAt: nullableText,
      cleanupConfirmedAt: nullableText,
    })
  ),
  versions: z.array(
    z.object({ versionId: text, documentId: text, versionNumber: number, createdAt: text })
  ),
})
const page = <T extends z.ZodType>(item: T) => z.object({ items: z.array(item), hasMore: flag })
const taskPage = page(task).extend({
  queuedCount: number.int().nonnegative().default(0),
  queuePaused: flag.default(false),
  confirmedRequestId: nullableText.default(null),
})
const credentials = z.object({ temporaryPassword: text })
const routes: [RegExp, z.ZodType, z.ZodType?][] = [
  [/^auth\/csrf$/, z.object({ token: text })],
  [/^auth\/(me|login)$/, user],
  [/^admin\/users$/, page(user), credentials.extend({ user })],
  [/^admin\/users\/[^/]+\/reset-password$/, credentials],
  [/^admin\/users\/[^/]+\/status$/, user],
  [/^admin\/users\/[^/]+\/model-access$/, z.array(text)],
  [/^(admin\/users\/[^/]+\/)?budget$/, budget],
  [/^models$/, z.array(model)],
  [/^admin\/models$/, z.array(adminModel), adminModel],
  [/^admin\/models\/[^/]+$/, adminModel],
  [/^admin\/model-settings$/, settings],
  [/^agents$/, page(agent), agent],
  [/^agents\/[^/]+$/, agent],
  [/^projects$/, page(project), project],
  [/^projects\/[^/]+$/, project],
  [/^projects\/[^/]+\/conversations$/, page(conversation)],
  [/^conversations$/, page(conversation), id],
  [/^conversations\/[^/]+$/, conversation],
  [/^conversations\/[^/]+\/tasks$/, taskPage, id],
  [/^conversations\/[^/]+\/messages$/, page(message)],
  [/^tasks\/[^/]+\/cancel$/, task],
  [/^tasks\/[^/]+\/calls$/, z.array(call)],
  [/^tasks\/[^/]+\/parses$/, z.array(parse)],
  [/^tasks\/[^/]+\/parses\/[^/]+$/, z.object({ text, nextOffset: number, hasMore: flag })],
  [/^admin\/tasks$/, page(diagnosticTask)],
  [/^(admin\/)?tasks\/[^/]+\/diagnostics$/, diagnostic],
  [/^materials$/, page(material), material],
  [/^projects\/[^/]+\/materials$/, page(material)],
  [/^(conversations|projects)\/[^/]+\/documents$/, page(version)],
  [/^document-versions$/, page(version)],
  [/^document-versions\/[^/]+$/, version.extend({ markdown: text })],
]

export function responseSchema(path: string, method: string): z.ZodType | undefined {
  const route = path.split('?')[0]?.slice('/api/'.length) ?? ''
  const match = routes.find(([pattern]) => pattern.test(route))
  return method === 'POST' ? (match?.[2] ?? match?.[1]) : match?.[1]
}

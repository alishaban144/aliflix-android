import { z } from 'zod';
const reference = z.object({ title:z.string().max(160), url:z.url().max(600).refine(v=>new URL(v).protocol==='https:'), license:z.string().max(240) }).strict();
export const TalkChatSchema = z.object({
  id:z.string().uuid(), title:z.string().trim().min(1).max(100),
  updatedAt:z.number().int().nonnegative(),
  lines:z.array(z.object({role:z.enum(['user','assistant']),text:z.string().min(1).max(1200),sources:z.array(reference).max(2).default([])}).strict()).min(1).max(12),
}).strict();
export const TalkChatsRequestSchema = z.discriminatedUnion('action',[
  z.object({action:z.literal('list')}).strict(),
  z.object({action:z.literal('save'),chat:TalkChatSchema}).strict(),
  z.object({action:z.literal('read'),id:z.string().uuid()}).strict(),
  z.object({action:z.literal('delete'),id:z.string().uuid()}).strict(),
  z.object({action:z.literal('clear')}).strict(),
]);
export type TalkChat = z.infer<typeof TalkChatSchema>;

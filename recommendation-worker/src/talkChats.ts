import { z } from 'zod';
const titleCard=z.object({id:z.number().int().positive(),type:z.enum(['movie','tv']),title:z.string().min(1).max(180),originalTitle:z.string().min(1).max(180).optional(),year:z.string().max(4),posterPath:z.string().max(200).regex(/^\/(?!\/)/).optional()}).strict();
const reference = z.object({ title:z.string().max(160), url:z.url().max(600).refine(v=>new URL(v).protocol==='https:'), license:z.string().max(240) }).strict();
export const TalkChatSchema = z.object({
  id:z.string().uuid(), title:z.string().trim().min(1).max(100),
  updatedAt:z.number().int().nonnegative(),
  lines:z.array(z.object({role:z.enum(['user','assistant']),text:z.string().min(1).max(6000),sources:z.array(reference).max(8).default([]),stopped:z.boolean().optional(),titles:z.array(titleCard).max(24).optional()}).strict()).min(1).max(64),
}).strict();
export const TalkChatsRequestSchema = z.discriminatedUnion('action',[
  z.object({action:z.literal('list')}).strict(),
  z.object({action:z.literal('save'),chat:TalkChatSchema}).strict(),
  z.object({action:z.literal('read'),id:z.string().uuid()}).strict(),
  z.object({action:z.literal('delete'),id:z.string().uuid()}).strict(),
  z.object({action:z.literal('clear')}).strict(),
]);
export type TalkChat = z.infer<typeof TalkChatSchema>;
/** Party identities distinguish device connections; archives belong to the
 * stable, verified account hash shared by all of those connections. */
export function talkChatAccountOwner(sub:string):string {
 const match=/^account:([A-Za-z0-9_-]{43}):[0-9a-f-]{36}$/i.exec(sub);
 if(!match)throw Error('UNAUTHORIZED');
 return 'account:'+match[1];
}

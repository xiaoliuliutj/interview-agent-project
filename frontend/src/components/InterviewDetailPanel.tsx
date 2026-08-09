import { motion } from 'framer-motion';
import { Clock, MessageSquare } from 'lucide-react';
import type { InterviewDetail } from '../api/history';
import { formatDateTime } from '../utils/date';

export default function InterviewDetailPanel({ interview }: { interview: InterviewDetail }) {
  const { session, turns } = interview;
  return <div className="space-y-6">
    <section className="rounded-2xl bg-gradient-to-r from-primary-500 to-indigo-600 p-6 text-white">
      <h2 className="text-xl font-bold">文本面试记录</h2>
      <div className="mt-4 grid gap-3 text-sm text-white/90 sm:grid-cols-3"><span>状态：{session.status}</span><span>难度：{session.difficulty}</span><span>计划主问题：{session.totalQuestions}</span></div>
      <p className="mt-4 text-sm text-white/80">面试评分、用户画像和检索证据仅由下层 Agent 保存，不会在这里展示。</p>
    </section>
    <section><h3 className="mb-4 flex items-center gap-2 font-semibold text-slate-800 dark:text-white"><MessageSquare className="h-5 w-5 text-primary-500" />问答记录</h3>{turns.length === 0 ? <p className="rounded-xl bg-slate-50 p-5 text-sm text-slate-500 dark:bg-slate-800">当前还没有已提交的回答。</p> : <div className="space-y-4">{turns.map((turn, index) => <motion.article key={`${turn.index}-${turn.question}`} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: index * .04 }} className="rounded-2xl bg-white p-5 shadow-sm dark:bg-slate-800"><div className="flex items-center justify-between gap-3"><span className="rounded-full bg-primary-50 px-3 py-1 text-xs font-medium text-primary-600 dark:bg-primary-900/30">{turn.stage}</span>{turn.answeredAt && <span className="flex items-center gap-1 text-xs text-slate-400"><Clock className="h-3.5 w-3.5" />{formatDateTime(turn.answeredAt)}</span>}</div><h4 className="mt-4 font-medium leading-relaxed text-slate-800 dark:text-white">{turn.index + 1}. {turn.question}</h4><div className="mt-4 rounded-xl bg-slate-50 p-4 dark:bg-slate-900/40"><p className="mb-1 text-xs text-slate-500">你的回答</p><p className="whitespace-pre-wrap text-sm leading-relaxed text-slate-700 dark:text-slate-300">{turn.answer ?? '未回答'}</p></div></motion.article>)}</div>}</section>
  </div>;
}

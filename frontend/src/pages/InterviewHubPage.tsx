import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { FileStack, Loader2, Sparkles } from 'lucide-react';

import { interviewApi, type TextSessionMeta } from '../api/interview';
import type { SkillDTO } from '../api/skill';
import { formatDateTime } from '../utils/date';
import {
  CUSTOM_SKILL_ID,
  DIFFICULTY_OPTIONS,
  useInterviewConfig,
} from '../hooks/useInterviewConfig';

function isResumable(session: TextSessionMeta): boolean {
  return session.status === 'ACTIVE' || session.status === 'PAUSED';
}

export default function InterviewHubPage() {
  const navigate = useNavigate();
  const config = useInterviewConfig();
  const [recentSessions, setRecentSessions] = useState<TextSessionMeta[]>([]);
  const [loadingRecent, setLoadingRecent] = useState(true);
  const [error, setError] = useState('');

  const loadRecent = useCallback(async () => {
    setLoadingRecent(true);
    try {
      setRecentSessions((await interviewApi.listSessions()).slice(0, 5));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '加载面试记录失败');
    } finally {
      setLoadingRecent(false);
    }
  }, []);

  useEffect(() => {
    void loadRecent();
  }, [loadRecent]);

  const startInterview = () => {
    if (config.isCustomStartDisabled || !config.resumeId || !config.targetRole.trim()) return;
    navigate('/interview', {
      state: {
        resumeId: config.resumeId,
        interviewConfig: {
          skillId: config.skillId,
          difficulty: config.difficulty,
          questionCount: config.questionCount,
          targetRole: config.targetRole.trim(),
          interviewDurationMinutes: config.plannedDuration,
          jdText: config.skillId === CUSTOM_SKILL_ID ? config.customJdText.trim() : undefined,
          customCategories: config.skillId === CUSTOM_SKILL_ID ? config.customCategories : [],
        },
      },
    });
  };

  const skillName = (session: TextSessionMeta) => (
    config.skills.find((skill: SkillDTO) => skill.id === session.skillId)?.name
      ?? session.skillId
      ?? '通用面试'
  );

  return (
    <main className="mx-auto max-w-5xl space-y-8">
      <header>
        <h1 className="flex items-center gap-3 text-2xl font-bold text-slate-800 dark:text-white"><Sparkles className="h-7 w-7 text-primary-500" />文本模拟面试</h1>
        <p className="mt-1 text-slate-500 dark:text-slate-400">基于当前简历、Agent 记忆和知识库进行多轮练习。</p>
      </header>

      <section className="space-y-6 rounded-2xl border border-slate-100 bg-white p-6 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        <div className="grid gap-4 md:grid-cols-2">
          <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">目标岗位
            <input value={config.targetRole} onChange={event => config.setTargetRole(event.target.value)} placeholder="例如：Java 后端实习生" className="mt-2 w-full rounded-xl border border-slate-200 p-3 font-normal dark:border-slate-700 dark:bg-slate-900 dark:text-white" />
          </label>
          <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">当前简历
            <select value={config.resumeId ?? ''} onChange={event => config.setResumeId(event.target.value || undefined)} className="mt-2 w-full rounded-xl border border-slate-200 p-3 font-normal dark:border-slate-700 dark:bg-slate-900 dark:text-white">
              <option value="">请选择当前简历</option>
              {config.resumes.map(resume => <option key={resume.id} value={resume.id}>{resume.filename ?? resume.id}</option>)}
            </select>
          </label>
        </div>

        <div>
          <p className="mb-3 text-sm font-semibold text-slate-700 dark:text-slate-200">面试方向</p>
          {config.loadingSkills ? <Loader2 className="h-5 w-5 animate-spin text-primary-500" /> : (
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
              {config.skills.map(skill => <button key={skill.id} onClick={() => config.setSkillId(skill.id)} className={`rounded-xl border-2 p-3 text-left text-sm ${config.skillId === skill.id ? 'border-primary-500 bg-primary-50 dark:bg-primary-900/20' : 'border-slate-200 dark:border-slate-700'}`}>{skill.name}</button>)}
              <button onClick={() => config.setSkillId(CUSTOM_SKILL_ID)} className={`rounded-xl border-2 border-dashed p-3 text-left text-sm ${config.isCustomSkill ? 'border-primary-500 bg-primary-50 dark:bg-primary-900/20' : 'border-slate-200 dark:border-slate-700'}`}>自定义 JD</button>
            </div>
          )}
        </div>

        {config.isCustomSkill && (
          <div className="space-y-3 rounded-xl bg-slate-50 p-4 dark:bg-slate-900/40">
            <textarea rows={4} value={config.customJdText} onChange={event => config.setCustomJdText(event.target.value)} placeholder="粘贴岗位描述（至少 50 个字符）" className="w-full rounded-xl border border-slate-200 bg-white p-3 text-sm dark:border-slate-700 dark:bg-slate-800 dark:text-white" />
            <button onClick={() => void config.handleParseJd().catch(value => setError(value instanceof Error ? value.message : 'JD 解析失败'))} disabled={config.parsingJd} className="rounded-lg bg-primary-500 px-4 py-2 text-sm text-white disabled:opacity-50">{config.parsingJd ? '解析中…' : '解析 JD'}</button>
            {config.customCategories.length > 0 && <p className="text-xs text-slate-500">已生成 {config.customCategories.length} 个考察方向。</p>}
          </div>
        )}

        <div>
          <p className="mb-3 text-sm font-semibold text-slate-700 dark:text-slate-200">难度</p>
          <div className="grid grid-cols-3 gap-2">
            {DIFFICULTY_OPTIONS.map(option => <button key={option.value} onClick={() => config.setDifficulty(option.value)} className={`rounded-xl border-2 p-3 text-center ${config.difficulty === option.value ? 'border-primary-500 bg-primary-50 dark:bg-primary-900/20' : 'border-slate-200 dark:border-slate-700'}`}><span className="block text-sm font-medium">{option.label}</span><span className="text-xs text-slate-400">{option.desc}</span></button>)}
          </div>
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <label className="text-sm text-slate-600 dark:text-slate-300">主问题数量
            <input type="number" min={2} max={20} value={config.questionCount} onChange={event => config.setQuestionCount(Number(event.target.value))} className="mt-2 w-full rounded-lg border border-slate-200 p-2 dark:border-slate-700 dark:bg-slate-900 dark:text-white" />
          </label>
          <label className="text-sm text-slate-600 dark:text-slate-300">面试时长（分钟）
            <input type="number" min={15} max={120} value={config.plannedDuration} onChange={event => config.setPlannedDuration(Number(event.target.value))} className="mt-2 w-full rounded-lg border border-slate-200 p-2 dark:border-slate-700 dark:bg-slate-900 dark:text-white" />
          </label>
        </div>

        {error && <p className="text-sm text-red-500">{error}</p>}
        <button onClick={startInterview} disabled={config.isCustomStartDisabled || !config.resumeId || !config.targetRole.trim() || config.questionCount < 2 || config.plannedDuration < 15} className="rounded-xl bg-primary-500 px-6 py-3 font-semibold text-white disabled:opacity-50">开始文本面试</button>
      </section>

      <section className="rounded-2xl border border-slate-100 bg-white p-6 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        <div className="mb-4 flex items-center justify-between"><h2 className="font-semibold text-slate-800 dark:text-white">最近文本面试</h2><Link to="/interviews" className="text-sm text-primary-500">查看全部</Link></div>
        {loadingRecent ? <Loader2 className="h-5 w-5 animate-spin text-primary-500" /> : recentSessions.length === 0 ? <p className="text-sm text-slate-500">暂无记录。</p> : (
          <div className="space-y-3">
            {recentSessions.map(session => {
              const resumable = isResumable(session);
              return <button key={session.sessionId} onClick={() => navigate(resumable ? '/interview' : `/interviews/${session.sessionId}`, { state: resumable ? { sessionIdToResume: session.sessionId } : undefined })} className="flex w-full items-center justify-between rounded-xl bg-slate-50 p-4 text-left hover:bg-slate-100 dark:bg-slate-900/40 dark:hover:bg-slate-900">
                <span className="flex items-center gap-3"><FileStack className="h-5 w-5 text-primary-500" /><span><span className="block font-medium text-slate-800 dark:text-white">{skillName(session)}</span><span className="text-xs text-slate-500">{formatDateTime(session.createdAt)}</span></span></span>
                <span className="text-sm text-slate-500">{resumable ? '继续' : '查看'}</span>
              </button>;
            })}
          </div>
        )}
      </section>
    </main>
  );
}

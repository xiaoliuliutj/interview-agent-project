import { useEffect } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { ChevronDown, ChevronUp, FileStack, Loader2, Sparkles, X } from 'lucide-react';
import { useInterviewConfig, CUSTOM_SKILL_ID, DIFFICULTY_OPTIONS, type Difficulty } from '../hooks/useInterviewConfig';

export type { Difficulty };

export interface UnifiedInterviewConfig {
  skillId?: string;
  skillName: string;
  difficulty: Difficulty;
  resumeId: string;
  questionCount: number;
  plannedDuration: number;
  targetRole: string;
  jdText?: string;
  customCategories: import('../api/skill').CategoryDTO[];
}

interface UnifiedInterviewModalProps {
  isOpen: boolean;
  onClose: () => void;
  onStart: (config: UnifiedInterviewConfig) => void;
  defaultResumeId?: string;
  title?: string;
  subtitle?: string;
  startButtonText?: string;
}

export default function UnifiedInterviewModal({
  isOpen,
  onClose,
  onStart,
  defaultResumeId,
  title = '开始文本面试',
  subtitle = '配置面试参数后开始',
  startButtonText = '开始面试',
}: UnifiedInterviewModalProps) {
  const config = useInterviewConfig({ defaultResumeId, autoLoad: false });

  useEffect(() => {
    if (!isOpen) return;
    if (defaultResumeId != null) {
      config.setResumeId(defaultResumeId);
      config.setShowMore(true);
    }
    void Promise.all([config.loadSkills(), config.loadResumes()]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, defaultResumeId]);

  const handleStart = () => {
    if (config.isCustomStartDisabled || !config.resumeId || !config.targetRole.trim()) return;
    onStart({
      skillId: config.skillId,
      skillName: config.selectedSkill?.name ?? '下层自动选择',
      difficulty: config.difficulty,
      resumeId: config.resumeId,
      questionCount: config.questionCount,
      plannedDuration: config.plannedDuration,
      targetRole: config.targetRole.trim(),
      jdText: config.isCustomSkill ? config.customJdText.trim() : undefined,
      customCategories: config.isCustomSkill ? config.customCategories : [],
    });
  };

  return (
    <AnimatePresence>
      {isOpen && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center bg-slate-950/50 p-4">
          <motion.div initial={{ opacity: 0, scale: .96 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: .96 }}
            className="w-full max-w-2xl max-h-[90vh] overflow-y-auto rounded-2xl bg-white shadow-xl dark:bg-slate-800">
            <div className="flex items-start justify-between border-b border-slate-100 p-6 dark:border-slate-700">
              <div><h2 className="text-xl font-bold text-slate-900 dark:text-white">{title}</h2><p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{subtitle}</p></div>
              <button onClick={onClose} aria-label="关闭" className="rounded-lg p-2 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700"><X className="h-5 w-5" /></button>
            </div>

            <div className="space-y-6 p-6">
              <section>
                <label className="mb-3 block text-sm font-semibold text-slate-700 dark:text-slate-200">面试方向</label>
                {config.loadingSkills ? <Loader2 className="h-5 w-5 animate-spin text-primary-500" /> : (
                  <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                    {config.skills.map(skill => {
                      const Icon = config.getSkillIcon(skill.id);
                      const selected = skill.id === config.skillId;
                      return <button key={skill.id} onClick={() => config.setSkillId(skill.id)} className={`rounded-xl border-2 p-3 text-left ${selected ? 'border-primary-500 bg-primary-50 dark:bg-primary-900/20' : 'border-slate-200 dark:border-slate-700'}`}>
                        <span className="flex items-center gap-2 text-sm font-medium text-slate-800 dark:text-white">{Icon ? <Icon className="h-4 w-4" /> : <Sparkles className="h-4 w-4" />}{skill.name}</span>
                      </button>;
                    })}
                    <button onClick={() => config.setSkillId(CUSTOM_SKILL_ID)} className={`rounded-xl border-2 border-dashed p-3 text-left ${config.isCustomSkill ? 'border-primary-500 bg-primary-50 dark:bg-primary-900/20' : 'border-slate-200 dark:border-slate-700'}`}><span className="text-sm font-medium text-slate-700 dark:text-slate-200">自定义 JD</span></button>
                  </div>
                )}
              </section>

              {config.isCustomSkill && <section className="space-y-3 rounded-xl bg-slate-50 p-4 dark:bg-slate-900/40">
                <textarea value={config.customJdText} onChange={event => config.setCustomJdText(event.target.value)} rows={4} placeholder="粘贴职位描述（至少 50 个字符）" className="w-full resize-none rounded-xl border border-slate-200 bg-white p-3 text-sm dark:border-slate-700 dark:bg-slate-800 dark:text-white" />
                <button onClick={() => void config.handleParseJd().catch(error => alert(error instanceof Error ? error.message : 'JD 解析失败'))} disabled={config.parsingJd || !config.customJdText.trim()} className="flex items-center gap-2 rounded-lg bg-primary-500 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"><Sparkles className="h-4 w-4" />{config.parsingJd ? '解析中…' : '解析 JD'}</button>
                {config.customCategories.length > 0 && <div className="flex flex-wrap gap-2">{config.customCategories.map(category => <span key={`${category.label}-${category.priority}`} className="rounded-full bg-primary-100 px-3 py-1 text-xs text-primary-700">{category.label}</span>)}</div>}
              </section>}

              <section className="grid gap-4 sm:grid-cols-2">
                <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">目标岗位<input value={config.targetRole} onChange={event => config.setTargetRole(event.target.value)} placeholder="例如：Java 后端实习生" className="mt-2 w-full rounded-xl border border-slate-200 p-3 font-normal dark:border-slate-700 dark:bg-slate-800 dark:text-white" /></label>
                <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">简历<select value={config.resumeId ?? ''} onChange={event => config.setResumeId(event.target.value || undefined)} className="mt-2 w-full rounded-xl border border-slate-200 p-3 font-normal dark:border-slate-700 dark:bg-slate-800 dark:text-white"><option value="">请选择当前简历</option>{config.resumes.map(resume => <option key={resume.id} value={resume.id}>{resume.filename ?? resume.id}</option>)}</select></label>
              </section>

              <section><label className="mb-3 block text-sm font-semibold text-slate-700 dark:text-slate-200">难度</label><div className="grid grid-cols-3 gap-2">{DIFFICULTY_OPTIONS.map(option => <button key={option.value} onClick={() => config.setDifficulty(option.value)} className={`rounded-xl border-2 p-3 ${config.difficulty === option.value ? 'border-primary-500 bg-primary-50 dark:bg-primary-900/20' : 'border-slate-200 dark:border-slate-700'}`}><span className="block text-sm font-medium text-slate-800 dark:text-white">{option.label}</span><span className="text-xs text-slate-400">{option.desc}</span></button>)}</div></section>

              <button onClick={() => config.setShowMore(!config.showMore)} className="flex w-full items-center gap-2 text-sm text-slate-500"><span>更多参数</span>{config.showMore ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}<span className="flex-1 border-t border-slate-200 dark:border-slate-700" /></button>
              {config.showMore && <section className="grid gap-4 rounded-xl bg-slate-50 p-4 dark:bg-slate-900/40 sm:grid-cols-2"><label className="text-sm text-slate-600 dark:text-slate-300">总题量上限（含追问）<input type="number" min={2} max={20} value={config.questionCount} onChange={event => config.setQuestionCount(Number(event.target.value))} className="mt-2 w-full rounded-lg border border-slate-200 p-2 dark:border-slate-700 dark:bg-slate-800 dark:text-white" /></label><label className="text-sm text-slate-600 dark:text-slate-300">面试时长（分钟）<input type="number" min={15} max={120} value={config.plannedDuration} onChange={event => config.setPlannedDuration(Number(event.target.value))} className="mt-2 w-full rounded-lg border border-slate-200 p-2 dark:border-slate-700 dark:bg-slate-800 dark:text-white" /></label><div className="flex items-center gap-2 text-xs text-slate-500"><FileStack className="h-4 w-4" />系统会按六阶段动态推进</div></section>}
            </div>

            <div className="flex gap-3 border-t border-slate-100 bg-slate-50 p-6 dark:border-slate-700 dark:bg-slate-900/40"><button onClick={onClose} className="flex-1 rounded-xl border border-slate-200 px-4 py-3 text-sm dark:border-slate-700 dark:text-slate-200">取消</button><button onClick={handleStart} disabled={config.isCustomStartDisabled || !config.resumeId || !config.targetRole.trim() || config.questionCount < 2 || config.plannedDuration < 15} className="flex-1 rounded-xl bg-primary-500 px-4 py-3 text-sm font-semibold text-white disabled:opacity-50">{startButtonText}</button></div>
          </motion.div>
        </div>
      )}
    </AnimatePresence>
  );
}

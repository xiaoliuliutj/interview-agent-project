import { useEffect, useState } from 'react';
import { skillApi, type CategoryDTO, type SkillDTO } from '../api/skill';
import { historyApi, type ResumeListItem } from '../api/history';
import { getSkillIcon } from '../utils/skillIcons';

export type Difficulty = 'junior' | 'mid' | 'senior';

export const DIFFICULTY_OPTIONS: { value: Difficulty; label: string; desc: string }[] = [
  { value: 'junior', label: '校招', desc: '基础优先' },
  { value: 'mid', label: '中级', desc: '基础与场景平衡' },
  { value: 'senior', label: '高级', desc: '场景与项目优先' },
];

export const CUSTOM_SKILL_ID = 'custom';
export const MIN_JD_LENGTH = 50;

export function useInterviewConfig(options?: { defaultResumeId?: string; autoLoad?: boolean }) {
  const { defaultResumeId, autoLoad = true } = options ?? {};
  const [skillId, setSkillId] = useState<string | undefined>(undefined);
  const [difficulty, setDifficulty] = useState<Difficulty>('mid');
  const [skills, setSkills] = useState<SkillDTO[]>([]);
  const [loadingSkills, setLoadingSkills] = useState(false);
  const [showMore, setShowMore] = useState(false);
  const [resumeId, setResumeId] = useState<string | undefined>(defaultResumeId);
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [questionCount, setQuestionCount] = useState(20);
  const [plannedDuration, setPlannedDuration] = useState(30);
  const [targetRole, setTargetRole] = useState('');
  const [customJdText, setCustomJdText] = useState('');
  const [parsedCustomJdText, setParsedCustomJdText] = useState('');
  const [customCategories, setCustomCategories] = useState<CategoryDTO[]>([]);
  const [parsingJd, setParsingJd] = useState(false);

  const isCustomSkill = skillId === CUSTOM_SKILL_ID;
  const jdNeedsReparse = parsedCustomJdText.length > 0 && customJdText !== parsedCustomJdText;
  const isCustomStartDisabled = isCustomSkill &&
    (customCategories.length === 0 || jdNeedsReparse || parsingJd);

  const loadSkills = async () => {
    setLoadingSkills(true);
    try {
      const data = await skillApi.listSkills();
      setSkills(data);
      return data;
    } finally {
      setLoadingSkills(false);
    }
  };

  const loadResumes = async () => {
    const data = await historyApi.getResumes();
    setResumes(data);
    return data;
  };

  const handleParseJd = async () => {
    if (customJdText.trim().length < MIN_JD_LENGTH) {
      throw new Error(`JD 至少需要 ${MIN_JD_LENGTH} 个字符`);
    }
    setParsingJd(true);
    try {
      const categories = await skillApi.parseJd(customJdText.trim());
      setCustomCategories(categories);
      setParsedCustomJdText(customJdText);
    } finally {
      setParsingJd(false);
    }
  };

  useEffect(() => {
    if (!autoLoad) return;
    if (defaultResumeId != null) {
      setResumeId(defaultResumeId);
      setShowMore(true);
    }
    void Promise.all([loadSkills(), loadResumes()]);
    // These actions intentionally run once when the configuration becomes active.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoLoad, defaultResumeId]);

  return {
    skillId, setSkillId, difficulty, setDifficulty, skills, setSkills, loadingSkills,
    showMore, setShowMore, resumeId, setResumeId, resumes,
    questionCount, setQuestionCount, plannedDuration, setPlannedDuration,
    targetRole, setTargetRole,
    customJdText, setCustomJdText, parsedCustomJdText, customCategories,
    parsingJd, jdNeedsReparse, isCustomStartDisabled, isCustomSkill,
    loadSkills, loadResumes, handleParseJd, getSkillIcon,
    selectedSkill: skills.find(skill => skill.id === skillId),
  };
}

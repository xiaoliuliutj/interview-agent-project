import type { CategoryDTO } from '../api/skill';

export interface InterviewQuestion {
  questionIndex: number;
  question: string;
  type: 'AGENT';
  category: string;
  userAnswer: string | null;
  evaluationSummary?: string | null;
  score?: number | null;
}

export interface InterviewSession {
  sessionId: string;
  resumeId: string;
  skillId: string | null;
  difficulty: string;
  totalQuestions: number;
  status: 'INITIALIZING' | 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'FAILED';
  stateVersion: number;
  currentQuestion: string | null;
  currentStage: string | null;
  issuedQuestionCount: number;
  primaryQuestionCount: number;
  followupCount: number;
  finalEvaluation: InterviewFinalEvaluation;
  createdAt: string;
  updatedAt: string;
  currentQuestionIndex: number;
  questions: InterviewQuestion[];
}

export interface InterviewFinalEvaluation {
  overallScore?: number;
  summary?: string;
  strengths?: string[];
  weaknesses?: string[];
  suggestions?: string[];
}

export interface CreateInterviewRequest {
  resumeId: string;
  targetRole: string;
  interviewDurationMinutes: number;
  questionCount: number;
  difficulty: string;
  skillId?: string;
  jdText?: string;
  customCategories: CategoryDTO[];
}

export interface SubmitAnswerRequest {
  sessionId: string;
  answer: string;
  runId: string;
}

export interface SubmitAnswerResponse {
  session: InterviewSession;
  hasNextQuestion: boolean;
  nextQuestion: InterviewQuestion | null;
}

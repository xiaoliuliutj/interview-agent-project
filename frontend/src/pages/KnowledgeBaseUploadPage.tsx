import { useState } from 'react';
import { knowledgeBaseApi } from '../api/knowledgebase';
import type { UploadKnowledgeBaseResponse, WebFetchResult } from '../api/knowledgebase';
import FileUploadCard from '../components/FileUploadCard';

interface KnowledgeBaseUploadPageProps {
  onUploadComplete: (result: UploadKnowledgeBaseResponse) => void;
  onBack: () => void;
}

export default function KnowledgeBaseUploadPage({ onUploadComplete, onBack }: KnowledgeBaseUploadPageProps) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [url, setUrl] = useState('');
  const [webLoading, setWebLoading] = useState(false);
  const [webPreview, setWebPreview] = useState<WebFetchResult | null>(null);

  const handleUpload = async (file: File, name?: string) => {
    setUploading(true);
    setError('');

    try {
      const data = await knowledgeBaseApi.uploadKnowledgeBase(file, name);
      onUploadComplete(data);
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : '上传失败，请重试';
      setError(errorMessage);
      setUploading(false);
    }
  };

  const handleReadWeb = async () => {
    if (!url.trim()) return;
    setWebLoading(true);
    setError('');
    setWebPreview(null);
    try {
      const preview = await knowledgeBaseApi.fetchWebPage(url.trim());
      setWebPreview(preview);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '网页读取失败');
    } finally {
      setWebLoading(false);
    }
  };

  const handleImportWeb = async () => {
    if (!webPreview) return;
    setWebLoading(true);
    setError('');
    try {
      const safeName = (webPreview.title || 'web-article').replace(/[\\/:*?"<>|]/g, '_').slice(0, 80);
      const file = new File([webPreview.markdown], `${safeName || 'web-article'}.md`, {type: 'text/markdown'});
      const data = await knowledgeBaseApi.uploadKnowledgeBase(file, webPreview.title, undefined, webPreview);
      onUploadComplete(data);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '网页知识库导入失败');
    } finally {
      setWebLoading(false);
    }
  };

  return (
    <div className="space-y-8">
      <FileUploadCard
      title="上传知识库"
      subtitle="上传文档，面试 Agent 会在生成相关题目时检索其中内容"
      accept=".pdf,.doc,.docx,.txt,.md"
      formatHint="支持 PDF、DOCX、DOC、TXT、MD"
      maxSizeHint="最大 50MB"
      uploading={uploading}
      uploadButtonText="开始上传"
      selectButtonText="选择文件"
      showNameInput={true}
      nameLabel="知识库名称（可选）"
      namePlaceholder="留空则使用文件名"
      error={error}
      onUpload={handleUpload}
      onBack={onBack}
      />
      <section className="max-w-3xl mx-auto bg-white dark:bg-slate-800 rounded-2xl p-8 shadow-lg">
        <h2 className="text-xl font-semibold text-slate-900 dark:text-white">从公开网页读取</h2>
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
          Agent 只读取你明确提供的公开 HTTP(S) 页面，并先生成 Markdown 预览；不会执行网页脚本。
        </p>
        <div className="mt-5 flex gap-3">
          <input
            value={url}
            onChange={(event) => setUrl(event.target.value)}
            onKeyDown={(event) => { if (event.key === 'Enter') void handleReadWeb(); }}
            placeholder="https://example.com/article"
            className="flex-1 px-4 py-3 border border-slate-200 dark:border-slate-600 rounded-xl bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
            disabled={webLoading}
          />
          <button
            type="button"
            onClick={() => void handleReadWeb()}
            disabled={webLoading || !url.trim()}
            className="px-5 py-3 rounded-xl bg-indigo-500 text-white font-medium disabled:opacity-50"
          >
            {webLoading ? '读取中…' : '读取网页'}
          </button>
        </div>
        {webPreview && (
          <div className="mt-6 border border-slate-200 dark:border-slate-600 rounded-xl overflow-hidden">
            <div className="p-4 bg-slate-50 dark:bg-slate-700/50">
              <h3 className="font-semibold text-slate-900 dark:text-white">{webPreview.title}</h3>
              <p className="mt-1 text-xs text-slate-500 break-all">{webPreview.url}</p>
              <p className="mt-1 text-xs text-slate-500">{webPreview.characterCount.toLocaleString()} 字符 · {new Date(webPreview.fetchedAt).toLocaleString()}</p>
            </div>
            <pre className="max-h-96 overflow-auto whitespace-pre-wrap p-4 text-sm text-slate-700 dark:text-slate-200">{webPreview.markdown}</pre>
            <div className="p-4 flex items-center justify-between gap-4 border-t border-slate-200 dark:border-slate-600">
              <p className="text-xs text-amber-600 dark:text-amber-400">请确认内容和来源可信后再导入。</p>
              <button type="button" onClick={() => void handleImportWeb()} disabled={webLoading}
                className="shrink-0 px-4 py-2 rounded-lg bg-emerald-500 text-white font-medium disabled:opacity-50">
                确认并导入知识库
              </button>
            </div>
          </div>
        )}
      </section>
    </div>
  );
}

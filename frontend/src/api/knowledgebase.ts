import {request} from './request';

export type VectorStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'DELETING' | 'DELETE_FAILED';
export type SortOption = 'time' | 'size';

export interface KnowledgeBaseItem {
  id: number;
  name: string;
  category: string | null;
  originalFilename: string;
  fileSize: number;
  contentType: string;
  uploadedAt: string;
  updatedAt: string;
  vectorStatus: VectorStatus;
  vectorError: string | null;
  chunkCount: number;
}

export interface KnowledgeBaseStats {
  totalCount: number;
  completedCount: number;
  processingCount: number;
  failedCount: number;
}

export interface UploadKnowledgeBaseResponse {
  knowledgeBase: {
    id: number;
    name: string;
    category: string;
    fileSize: number;
    contentLength: number;
  };
}

export const knowledgeBaseApi = {
  async uploadKnowledgeBase(file: File, name?: string, category?: string): Promise<UploadKnowledgeBaseResponse> {
    const formData = new FormData();
    formData.append('file', file);
    if (name) formData.append('name', name);
    if (category) formData.append('category', category);
    return request.upload<UploadKnowledgeBaseResponse>('/api/knowledgebase/upload', formData);
  },

  async downloadKnowledgeBase(id: number): Promise<Blob> {
    const response = await request.getInstance().get(`/api/knowledgebase/${id}/download`, {
      responseType: 'blob',
      skipResultTransform: true,
    } as never);
    return response.data;
  },

  async getAllKnowledgeBases(sortBy?: SortOption, vectorStatus?: VectorStatus): Promise<KnowledgeBaseItem[]> {
    const params = new URLSearchParams();
    if (sortBy) params.append('sortBy', sortBy);
    if (vectorStatus) params.append('vectorStatus', vectorStatus);
    const query = params.toString();
    return request.get<KnowledgeBaseItem[]>(`/api/knowledgebase/list${query ? `?${query}` : ''}`);
  },

  async deleteKnowledgeBase(id: number): Promise<void> {
    return request.delete(`/api/knowledgebase/${id}`);
  },

  async getAllCategories(): Promise<string[]> {
    return request.get<string[]>('/api/knowledgebase/categories');
  },

  async getByCategory(category: string): Promise<KnowledgeBaseItem[]> {
    return request.get<KnowledgeBaseItem[]>(`/api/knowledgebase/category/${encodeURIComponent(category)}`);
  },

  async updateCategory(id: number, category: string | null): Promise<void> {
    return request.put(`/api/knowledgebase/${id}/category`, {category});
  },

  async search(keyword: string): Promise<KnowledgeBaseItem[]> {
    return request.get<KnowledgeBaseItem[]>(`/api/knowledgebase/search?keyword=${encodeURIComponent(keyword)}`);
  },

  async getStatistics(): Promise<KnowledgeBaseStats> {
    return request.get<KnowledgeBaseStats>('/api/knowledgebase/stats');
  },

  async revectorize(id: number): Promise<void> {
    return request.post(`/api/knowledgebase/${id}/revectorize`);
  },
};

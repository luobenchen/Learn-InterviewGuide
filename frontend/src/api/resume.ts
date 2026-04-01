import { request } from './request';
import type { UploadResponse } from '../types/resume';

export const resumeApi = {
  /**
   * 上传简历并获取分析结果
   */
  async uploadAndAnalyze(file: File): Promise<UploadResponse> {
    const formData = new FormData();
    formData.append('file', file);
    return request.upload<UploadResponse>('/api/resumes/upload', formData);
  },

  /**
   * 批量上传简历文件（新增接口）
   */
  async batchUploadAndAnalyze(files: File[]): Promise<any> {
    const formData = new FormData();
    files.forEach(file => {
      formData.append('files', file); // 必须是 files，后端 @RequestParam("files")
    });
    return request.upload<any>('/api/resumes/batch-upload', formData);
  },

  /**
   * 健康检查
   */
  async healthCheck(): Promise<{ status: string; service: string }> {
    return request.get('/api/resumes/health');
  },
};

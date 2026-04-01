import { useState } from 'react';
import { resumeApi } from '../api/resume';
import { getErrorMessage } from '../api/request';
import FileUploadCard from '../components/FileUploadCard';

interface UploadPageProps {
  onUploadComplete: (resumeId: number) => void;
}

export default function UploadPage({ onUploadComplete }: UploadPageProps) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');

  // 支持接受单文件或是多文件数组
  const handleUpload = async (fileOrFiles: unknown) => {
    setUploading(true);
    setError('');

    try {
      if (Array.isArray(fileOrFiles) && fileOrFiles.length > 0) {
        // 调用新的并发批量接口
        const files = fileOrFiles as File[];
        const dataArr = await resumeApi.batchUploadAndAnalyze(files);
        
        // 简单处理：如果有成的，跳到第一个成功的简历去
        const successItem = dataArr.find((item: any) => item.status !== 'FAILED');
        if (successItem && successItem.storage && successItem.storage.resumeId) {
            onUploadComplete(successItem.storage.resumeId);
            return;
        }
        throw new Error(dataArr[0]?.error || '批量全部上传失败，请重试');

      } else {
        // 老的单文件逻辑
        const file = fileOrFiles as File;
        const data = await resumeApi.uploadAndAnalyze(file);

        if (!data.storage || !data.storage.resumeId) {
          throw new Error('上传失败，请重试');
        }
        onUploadComplete(data.storage.resumeId);
      }
    } catch (err) {
      setError(getErrorMessage(err));
      setUploading(false);
    }
  };

  return (
    <FileUploadCard
      title="开始您的 AI 模拟面试"
      subtitle="上传 PDF 或 Word 简历，AI 将为您定制专属面试方案"
      accept=".pdf,.doc,.docx,.txt"
      formatHint="支持 PDF, DOCX, TXT"
      maxSizeHint="最大 10MB"
      uploading={uploading}
      uploadButtonText="开始上传"
      selectButtonText="选择简历文件"
      error={error}
      onUpload={handleUpload}
    />
  );
}

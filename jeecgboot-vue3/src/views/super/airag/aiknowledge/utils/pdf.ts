/**
 * PDF.js 封装：加载 PDF → 渲染指定页到 canvas → 提供翻页 + 跳页 API。
 *
 * 供 GbStandardPreview.vue 左侧 PDF 预览面板使用。
 * update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P2】PDF.js 接入----------- -->
 */
import * as pdfjsLib from 'pdfjs-dist';
import type { PDFDocumentProxy, RenderTask } from 'pdfjs-dist';

/**
 * 配置 worker。
 *
 * Vite 推荐用法：使用 `new URL(...)` + `import.meta.url`，
 * Vite 会自动把 worker 文件作为独立 asset 打包。
 * 注：worker 文件名随 pdfjs-dist 版本变化（4.x 前为 `.js`，4.x+ 为 `.mjs`）。
 * 当前安装版本（6.1.200）的 worker 文件为 `build/pdf.worker.min.mjs`，
 * 已在安装后于 node_modules/pdfjs-dist/build/ 验证存在。
 */
pdfjsLib.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url,
).toString();

/** 渲染缩放倍数，1 = 原始 PDF 尺寸；国标 PDF 多为 A4，1.2 看起来比较舒服 */
const DEFAULT_SCALE = 1.2;

export interface PdfDocument {
  /** PDF 总页数 */
  numPages: number;
  /**
   * 渲染指定页到 canvas。
   * @param pageNum 1-based 页码
   * @param canvas 目标 canvas 元素
   */
  renderPage: (pageNum: number, canvas: HTMLCanvasElement) => Promise<void>;
  /** 销毁底层资源（切换 PDF / 卸载组件时调用） */
  destroy: () => Promise<void>;
}

/**
 * 加载远程 PDF。
 * @param url PDF 文件的访问 URL
 * @returns PdfDocument 句柄
 */
export async function loadPdf(url: string): Promise<PdfDocument> {
  const loadingTask = pdfjsLib.getDocument({
    url,
    // 关闭节点辅助依赖，避免某些环境下的 warning
    disableAutoFetch: false,
    disableStream: false,
  });

  const pdfDoc: PDFDocumentProxy = await loadingTask.promise;

  // 记录当前正在进行的渲染任务，新渲染开始前取消上一次，避免竞争导致 canvas 闪烁/报错
  let currentRenderTask: RenderTask | null = null;

  async function renderPage(pageNum: number, canvas: HTMLCanvasElement): Promise<void> {
    if (pageNum < 1 || pageNum > pdfDoc.numPages) {
      return;
    }

    // 取消上一次未完成的渲染
    if (currentRenderTask) {
      try {
        currentRenderTask.cancel();
      } catch {
        /* ignore cancel error */
      }
      currentRenderTask = null;
    }

    const page = await pdfDoc.getPage(pageNum);
    const viewport = page.getViewport({ scale: DEFAULT_SCALE });

    // 准备 canvas：设置物理像素以匹配 viewport，并按设备像素比放大以获得清晰渲染
    const outputScale = window.devicePixelRatio || 1;
    canvas.width = Math.floor(viewport.width * outputScale);
    canvas.height = Math.floor(viewport.height * outputScale);
    canvas.style.width = `${Math.floor(viewport.width)}px`;
    canvas.style.height = `${Math.floor(viewport.height)}px`;

    const context = canvas.getContext('2d');
    if (!context) {
      return;
    }

    // 必要时初始化 transform 以支持高 DPI
    const transform = outputScale !== 1 ? [outputScale, 0, 0, outputScale, 0, 0] : undefined;

    currentRenderTask = page.render({
      canvasContext: context,
      viewport,
      transform,
    });

    try {
      await currentRenderTask.promise;
    } catch (err: any) {
      // RenderingCancelledException 是正常的，不算错误
      if (err?.name !== 'RenderingCancelledException') {
        throw err;
      }
    } finally {
      currentRenderTask = null;
    }
  }

  async function destroy(): Promise<void> {
    if (currentRenderTask) {
      try {
        currentRenderTask.cancel();
      } catch {
        /* ignore */
      }
      currentRenderTask = null;
    }
    try {
      await pdfDoc.destroy();
    } catch {
      /* ignore */
    }
  }

  return {
    numPages: pdfDoc.numPages,
    renderPage,
    destroy,
  };
}
// update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P2】PDF.js 接入----------- -->

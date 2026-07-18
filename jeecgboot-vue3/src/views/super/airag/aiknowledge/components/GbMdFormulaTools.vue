<!--update-begin---author:song ---date:2026-07-18  for：【GB线性入库】公式工具抽离（KaTeX 管线 + 符号转换）----------- -->
<script lang="ts">
  import MarkdownIt from 'markdown-it';
  // katex CJS/ESM 兼容
  import katexModule from 'katex';
  import 'katex/dist/katex.min.css';

  const katexApi: any = (katexModule as any)?.default || katexModule;

  // Markdown 正文渲染（公式单独用 KaTeX，避免 _ 被 markdown 吃掉）
  const mdRenderer = new MarkdownIt({
    html: true,
    linkify: true,
    breaks: true,
  });

  export function escapeHtml(s: string) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  export function renderKatex(tex: string, displayMode: boolean): string {
    const clean = String(tex || '')
      .trim()
      // 偶发双反斜杠
      .replace(/\\\\mathrm/g, '\\mathrm')
      .replace(/\\\\max/g, '\\max')
      .replace(/\\\\min/g, '\\min')
      .replace(/\\\\times/g, '\\times')
      .replace(/\\\\pm/g, '\\pm');
    try {
      if (!katexApi?.renderToString) {
        return `<code class="gb-katex-fail">${escapeHtml(clean)}</code>`;
      }
      return katexApi.renderToString(clean, {
        throwOnError: false,
        displayMode,
        strict: 'ignore',
        trust: true,
      });
    } catch {
      return `<code class="gb-katex-fail">${escapeHtml(clean)}</code>`;
    }
  }

  /**
   * 先抽出公式 → KaTeX HTML 占位 → markdown-it → 还原。
   * 占位符使用零宽字符包裹，避免被 markdown 转义/拆坏。
   */
  export function renderMarkdownWithKatex(raw: string): string {
    const src = normalizeFormulasForKatex(raw);
    const slots: string[] = [];
    const put = (html: string) => {
      const i = slots.length;
      slots.push(html);
      // 用特殊占位，markdown 几乎不会改
      return `K${i}`;
    };

    // 1) 块级 $$...$$
    let masked = src.replace(/\$\$([\s\S]+?)\$\$/g, (_m, tex) => {
      return put(`<div class="katex-block">${renderKatex(tex, true)}</div>`);
    });
    // 2) 行内 $...$
    masked = masked.replace(/\$([^\$\n]+?)\$/g, (m, tex) => {
      if (!String(tex).trim()) return m;
      return put(renderKatex(tex, false));
    });
    // 3) 无 $ 但已是 LaTeX 下标形态：T_{\mathrm{dm}} / I_{\mathrm{t}}
    masked = masked.replace(
      /(?<![\w\\])([A-Za-z])_\{(\\mathrm\{[^}]+\}|\\max|\\min|[A-Za-z0-9]+)\}(?![\w])/g,
      (_m, base, sub) => put(renderKatex(`${base}_{${sub}}`, false)),
    );

    let html = mdRenderer.render(masked);
    // 4) 还原（含被包进 <p> 的情况）
    html = html.replace(/K(\d+)/g, (_m, i) => slots[Number(i)] || '');
    // 5) 最后兜底：若仍有裸 $...$ 残留在 HTML 文本中，再渲染一次
    html = html.replace(/\$([^\$<]{1,80})\$/g, (m, tex) => {
      if (!tex || /[<>]/.test(tex)) return m;
      return renderKatex(tex, false);
    });
    return html;
  }

  /**
   * 轻度规范化 + 常见国标符号转 LaTeX，便于 KaTeX 识别。
   * 用户不必手写 LaTeX：T_dm / Tdm / I_t 等会尽量变成 $T_{\mathrm{dm}}$。
   */
  function normalizeFormulasForKatex(raw: string): string {
    if (!raw) return raw;
    let s = raw;
    s = s.replace(/\\\(([\s\S]*?)\\\)/g, (_m, inner) => `$${inner}$`);
    s = s.replace(/\\\[([\s\S]*?)\\\]/g, (_m, inner) => `$$${inner}$$`);
    // 全文自动识别（展示层）
    s = autoLatexPlainSymbols(s);
    return s;
  }

  /**
   * 把用户从 PDF 复制的“像公式”的纯文本，转成 KaTeX 可用片段（不含外层 $ 时也可）。
   * 例：T_dm → T_{\mathrm{dm}}；Tdm → T_{\mathrm{dm}}；I_t → I_{\mathrm{t}}
   */
  export function plainToLatex(input: string): string {
    let s = (input || '').trim();
    if (!s) return s;
    // 已是 latex 片段
    if (s.includes('\\') || (s.includes('{') && s.includes('}'))) {
      return s.replace(/^\$+/, '').replace(/\$+$/, '');
    }
    // Unicode 下标 → latex
    s = unicodeSubscriptsToLatex(s);
    // T_dm / U_up / I_t
    const m = s.match(/^([A-Za-zΑ-Ωα-ω])[_\s]*([A-Za-z0-9]{1,8})$/);
    if (m) {
      return `${m[1]}_{\\mathrm{${m[2]}}}`;
    }
    // Tdm / Uup / It（单字母+小写尾巴）
    const m2 = s.match(/^([A-ZΑ-Ω])([a-z]{1,6})$/);
    if (m2) {
      return `${m2[1]}_{\\mathrm{${m2[2]}}}`;
    }
    // 含 × ± 等
    s = s
      .replace(/×/g, '\\times ')
      .replace(/±/g, '\\pm ')
      .replace(/≤/g, '\\leq ')
      .replace(/≥/g, '\\geq ')
      .replace(/℃/g, '^{\\circ}\\mathrm{C}');
    return s;
  }

  function unicodeSubscriptsToLatex(s: string): string {
    const map: Record<string, string> = {
      '₀': '0', '₁': '1', '₂': '2', '₃': '3', '₄': '4',
      '₅': '5', '₆': '6', '₇': '7', '₈': '8', '₉': '9',
      'ₐ': 'a', 'ₑ': 'e', 'ₒ': 'o', 'ₓ': 'x',
      'ᵢ': 'i', 'ᵣ': 'r', 'ᵤ': 'u', 'ᵥ': 'v', 'ₜ': 't',
    };
    // Tₘₐₓ 这类
    if (/[₀₁₂₃₄₅₆₇₈₉ₐₑₒₓᵢᵣᵤᵥₜ]/.test(s)) {
      let base = '';
      let sub = '';
      for (const ch of s) {
        if (map[ch]) sub += map[ch];
        else base += ch;
      }
      if (base && sub) {
        return `${base}_{\\mathrm{${sub}}}`;
      }
    }
    return s;
  }

  /**
   * 全文：把未包在 $ 里的常见 “字母_下标 / 字母+小写尾巴” 转成 $...$
   * 跳过已在 $...$ 内的内容。
   */
  export function autoLatexPlainSymbols(raw: string): string {
    if (!raw) return raw;
    // 保护已有公式
    const protectedChunks: string[] = [];
    const mask = raw.replace(/\$\$[\s\S]*?\$\$|\$[^$\n]+\$/g, (m) => {
      protectedChunks.push(m);
      return `\u0000${protectedChunks.length - 1}\u0000`;
    });

    let s = mask;
    // A_bc / T_dm（避免匹配 URL 等：要求较短）
    s = s.replace(/(?<![\\$A-Za-z])([A-Za-z])_([A-Za-z0-9]{1,6})(?![A-Za-z0-9$])/g, (_m, a, b) => {
      return `$${a}_{\\mathrm{${b}}}$`;
    });
    // 独立成行的符号行：仅 Tdm / Uup
    s = s.replace(/(^|\n)([A-Z])([a-z]{1,5})(\n|$)/g, (m, p1, a, b, p4) => {
      // 避免普通英文词（The / And 等）：下标长度 1~4 且全小写
      if (b.length > 4) return m;
      return `${p1}$${a}_{\\mathrm{${b}}}$${p4}`;
    });

    s = s.replace(/\u0000(\d+)\u0000/g, (_m, i) => protectedChunks[Number(i)] || '');
    return s;
  }
</script>

<script lang="ts" setup>
  import { ref, computed, nextTick } from 'vue';
  import Icon from '@/components/Icon';
  import { useMessage } from '/@/hooks/web/useMessage';

  const props = defineProps<{
    /** 源码文本（v-model） */
    markdownText: string;
    /** 解析宿主 textarea（公式插入/选区定位都依赖它） */
    getTextarea: () => HTMLTextAreaElement | null;
  }>();
  const emit = defineEmits(['update:markdownText']);
  const { createMessage } = useMessage();

  /** 面板默认收起，需要改公式时再点开 */
  const open = ref(false);
  /** 粘贴框：用户从 PDF 复制后粘贴，一键转公式 */
  const formulaPaste = ref('');
  const selection = ref({ start: 0, end: 0, text: '' });

  /** 国标常见符号快捷插入（显示名 → 已包好的 $LaTeX$） */
  const formulaChips = [
    { label: 'Iₜ', latex: '$I_{\\mathrm{t}}$' },
    { label: 'Uᵤₚ', latex: '$U_{\\mathrm{up}}$' },
    { label: 'T_dm', latex: '$T_{\\mathrm{dm}}$' },
    { label: 'T_dl', latex: '$T_{\\mathrm{dl}}$' },
    { label: 'T_max', latex: '$T_{\\max}$' },
    { label: 'T_min', latex: '$T_{\\min}$' },
    { label: 'C', latex: '$C$' },
    { label: 'n', latex: '$n$' },
    { label: '×', latex: '$\\times$' },
    { label: '±', latex: '$\\pm$' },
    { label: '≤', latex: '$\\leq$' },
    { label: '≥', latex: '$\\geq$' },
    { label: '℃', latex: '$^{\\circ}\\mathrm{C}$' },
  ];

  const pasteLatexWrapped = computed(() => {
    const t = formulaPaste.value?.trim();
    if (!t) return '';
    const latex = plainToLatex(t);
    return latex.startsWith('$') ? latex : `$${latex}$`;
  });

  const pastePreviewHtml = computed(() => {
    if (!pasteLatexWrapped.value) return '';
    try {
      // 去掉外层 $ 后直接 KaTeX
      const tex = pasteLatexWrapped.value.replace(/^\$+/, '').replace(/\$+$/, '');
      return renderKatex(tex, false);
    } catch {
      return '';
    }
  });

  /** 宿主 textarea 选区变化时同步（select/mouseup/keyup 由宿主转发） */
  function notifySelection() {
    const el = props.getTextarea?.();
    if (!el) return;
    const start = el.selectionStart ?? 0;
    const end = el.selectionEnd ?? 0;
    selection.value = {
      start,
      end,
      text: (props.markdownText || '').slice(start, end),
    };
  }
  defineExpose({ notifySelection });

  function updateText(next: string) {
    emit('update:markdownText', next);
  }

  function insertAtCursor(snippet: string) {
    const el = props.getTextarea?.();
    const text = props.markdownText || '';
    let start = selection.value.start;
    let end = selection.value.end;
    if (el) {
      start = el.selectionStart ?? start;
      end = el.selectionEnd ?? end;
    }
    updateText(text.slice(0, start) + snippet + text.slice(end));
    nextTick(() => {
      const ta = props.getTextarea?.();
      if (!ta) return;
      const pos = start + snippet.length;
      ta.focus();
      ta.setSelectionRange(pos, pos);
      notifySelection();
    });
  }

  function replaceSelection(next: string) {
    const text = props.markdownText || '';
    const { start, end } = selection.value;
    if (end <= start) {
      insertAtCursor(next);
      return;
    }
    updateText(text.slice(0, start) + next + text.slice(end));
    nextTick(() => {
      const ta = props.getTextarea?.();
      if (!ta) return;
      ta.focus();
      ta.setSelectionRange(start, start + next.length);
      notifySelection();
    });
  }

  function insertConvertedPaste() {
    let t = formulaPaste.value?.trim();
    if (!t) {
      createMessage.info('请先从左侧 PDF 复制符号，粘贴到输入框');
      return;
    }
    // 若粘贴了整段条款，只抽第一行像符号的片段
    const lines = t.split(/\n+/).map((x) => x.trim()).filter(Boolean);
    if (lines.length > 1) {
      const sym = lines.find((l) => /^[A-Za-z][_\sA-Za-z0-9]{0,10}$/.test(l) || /^[A-Z][a-z]{1,5}$/.test(l));
      if (sym) t = sym;
      else t = lines[0];
    }
    formulaPaste.value = t;
    const wrapped = pasteLatexWrapped.value;
    insertAtCursor(wrapped + ' ');
    formulaPaste.value = '';
    createMessage.success('已插入公式，可切「预览」查看；确认后点「保存修正」');
  }

  function convertSelectionToLatex() {
    const t = selection.value.text;
    if (!t) {
      createMessage.info('请先选中从 PDF 粘贴的符号，例如 T_dm 或 Tdm');
      return;
    }
    const latex = plainToLatex(t.trim());
    const wrapped = latex.includes('$') ? latex : `$${latex}$`;
    replaceSelection(wrapped);
    createMessage.success('已转为公式，可切到「预览」查看效果');
  }

  function autoDetectFormulasInDoc() {
    const before = props.markdownText || '';
    const after = autoLatexPlainSymbols(before);
    if (after === before) {
      createMessage.info('未发现可自动转换的符号（或已是公式）');
      return;
    }
    updateText(after);
    createMessage.success('已自动识别常见下标符号，请到「预览」检查');
  }
</script>

<template>
  <div class="gb-formula-tools">
    <div class="gb-formula-tools-bar">
      <a-button size="small" type="primary" ghost @click="open = !open">
        <template #icon>
          <Icon :icon="open ? 'ant-design:up-outlined' : 'ant-design:function-outlined'" size="14" />
        </template>
        {{ open ? '收起公式工具' : '公式工具' }}
      </a-button>
      <slot name="actions" />
      <span class="gb-formula-tools-hint">需要改公式时再点「公式工具」</span>
    </div>

    <div v-show="open" class="gb-formula-panel">
      <div class="gb-formula-paste-row">
        <a-input
          v-model:value="formulaPaste"
          size="small"
          allow-clear
          placeholder="从左侧 PDF 复制符号粘贴，如 Tdm / T_dm / I_t"
          @pressEnter="insertConvertedPaste"
        />
        <a-button size="small" type="primary" @click="insertConvertedPaste">转成公式并插入</a-button>
      </div>
      <div v-if="pastePreviewHtml" class="gb-formula-live">
        <span>预览：</span>
        <span class="gb-formula-live-html" v-html="pastePreviewHtml"></span>
        <code class="gb-formula-latex-code">{{ pasteLatexWrapped }}</code>
      </div>
      <div class="gb-formula-actions">
        <a-button size="small" @click="autoDetectFormulasInDoc">全文自动识别</a-button>
        <a-button size="small" @click="convertSelectionToLatex">选中文本转公式</a-button>
      </div>
      <div class="gb-formula-chips">
        <span class="chips-label">常用：</span>
        <a-tag
          v-for="item in formulaChips"
          :key="item.label"
          class="formula-chip"
          color="blue"
          @click="insertAtCursor(item.latex)"
        >
          {{ item.label }}
        </a-tag>
      </div>
    </div>
  </div>
</template>

<style lang="less" scoped>
  .gb-formula-tools {
    flex-shrink: 0;
  }
  .gb-formula-tools-bar {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 12px;
    flex-wrap: wrap;
  }
  .gb-formula-tools-hint {
    font-size: 12px;
    color: #bfbfbf;
    margin-left: 4px;
  }
  .gb-formula-panel {
    margin: 0 12px 8px;
    padding: 10px 12px;
    background: #fafafa;
    border: 1px solid #e8e8e8;
    border-radius: 6px;
  }
  .gb-formula-paste-row {
    display: flex;
    gap: 8px;
    align-items: center;

    :deep(.ant-input-affix-wrapper),
    :deep(.ant-input) {
      flex: 1;
    }
  }
  .gb-formula-latex-code {
    font-size: 12px;
    color: #8c8c8c;
    margin-left: 8px;
  }
  .gb-formula-actions {
    margin-top: 8px;
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
  .gb-formula-chips {
    margin-top: 8px;
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: center;

    .chips-label {
      font-size: 12px;
      color: #8c8c8c;
    }
    .formula-chip {
      cursor: pointer;
      user-select: none;
    }
  }
  .gb-formula-live {
    margin-top: 8px;
    font-size: 13px;
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    min-height: 28px;
  }
  .gb-formula-live-html {
    padding: 2px 8px;
    background: #fff;
    border: 1px dashed #91caff;
    border-radius: 4px;
  }
</style>
<!--update-end---author:song ---date:2026-07-18  for：【GB线性入库】公式工具抽离----------- -->

/* eslint-disable @typescript-eslint/no-explicit-any */
import {
  Node,
  VueNodeViewRenderer,
  mergeAttributes,
  ToolboxItem,
  type Editor,
  type Range,
  type ExtensionOptions,
} from '@halo-dev/richtext-editor'
import FileDownloadNode from './components/FileDownloadNode.vue'
import { markRaw } from 'vue'
import RiDownload2Line from '~icons/ri/download-2-line'
import { formatFileSize } from './utils'

export const FileDownloadExtension = Node.create<ExtensionOptions>({
  name: 'fileDownload',

  group: 'block',

  atom: true,

  addAttributes() {
    return {
      url: {
        default: '',
        parseHTML: (element: HTMLElement) => {
          return (
            element.getAttribute('data-url') ||
            element.querySelector('a')?.getAttribute('href') ||
            ''
          )
        },
        renderHTML: (attributes: { url: string }) => ({
          'data-url': attributes.url || '',
        }),
      },
      name: {
        default: '',
        parseHTML: (element: HTMLElement) => {
          return (
            element.getAttribute('data-name') ||
            element.querySelector('a')?.getAttribute('download') ||
            ''
          )
        },
        renderHTML: (attributes: { name: string }) => ({
          'data-name': attributes.name || '',
        }),
      },
      size: {
        default: 0,
        parseHTML: (element: HTMLElement) => {
          return Number(element.getAttribute('data-size')) || 0
        },
        renderHTML: (attributes: { size: number }) => ({
          'data-size': String(attributes.size || 0),
        }),
      },
    }
  },

  parseHTML() {
    return [
      {
        tag: 'div[data-type="file-download"]',
      },
    ]
  },

  // Self-contained card: inline styles only, no script, renders on any theme.
  // Keep in sync with buildFileDownloadHtml() in utils.ts.
  renderHTML({ node, HTMLAttributes }) {
    const { url, name, size } = node?.attrs || {}
    const displayName = name || '下载文件'
    const sizeText = formatFileSize(size)

    return [
      'div',
      mergeAttributes(HTMLAttributes, {
        'data-type': 'file-download',
        'data-url': url || '',
        'data-name': displayName,
        'data-size': String(size || 0),
        style: 'margin:1rem 0;',
      }),
      [
        'a',
        {
          href: url || '#',
          download: displayName,
          target: '_blank',
          rel: 'noopener',
          style:
            'display:flex;align-items:center;gap:12px;border:1px solid #e5e7eb;border-radius:8px;padding:12px 16px;background:#f9fafb;text-decoration:none;color:inherit;',
        },
        [
          'span',
          {
            style:
              'flex:0 0 36px;width:36px;height:36px;border-radius:8px;background:#dbeafe;color:#2563eb;display:flex;align-items:center;justify-content:center;font-size:18px;line-height:1;',
          },
          '↓',
        ],
        [
          'span',
          { style: 'flex:1;min-width:0;display:flex;flex-direction:column;gap:2px;' },
          [
            'span',
            {
              style:
                'font-size:14px;font-weight:600;color:#111827;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;',
            },
            displayName,
          ],
          ['span', { style: 'font-size:12px;color:#6b7280;' }, sizeText],
        ],
        ['span', { style: 'flex:0 0 auto;font-size:14px;color:#3b82f6;' }, '下载'],
      ],
    ]
  },

  addNodeView() {
    return VueNodeViewRenderer(FileDownloadNode)
  },

  addCommands() {
    return {
      insertFileDownload:
        (attrs?: { url?: string; name?: string; size?: number }) =>
        ({ commands }: any) => {
          return commands.insertContent({
            type: this.name,
            attrs: { url: '', name: '', size: 0, ...attrs },
          })
        },
    } as Record<string, any>
  },

  addOptions() {
    return {
      ...this.parent?.(),
      getToolboxItems({ editor }: { editor: Editor }) {
        return [
          {
            priority: 51,
            component: markRaw(ToolboxItem),
            props: {
              editor,
              icon: markRaw(RiDownload2Line),
              title: '文件下载卡片',
              action: () => {
                ;(editor.chain().focus() as any).insertFileDownload().run()
              },
            },
          },
        ]
      },
      getCommandMenuItems() {
        return {
          priority: 121,
          icon: markRaw(RiDownload2Line),
          title: '文件下载卡片',
          keywords: ['file-download', 'download', 'wenjian', 'xiazai', '附件'],
          command: ({ editor, range }: { editor: Editor; range: Range }) => {
            ;(editor.chain().focus() as any).deleteRange(range).insertFileDownload().run()
          },
        }
      },
    }
  },
})

export default FileDownloadExtension

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
import ImageGalleryNode from './components/ImageGalleryNode.vue'
import { markRaw } from 'vue'
import RiGalleryLine from '~icons/ri/gallery-line'

interface GalleryImage {
  url: string
  alt: string
}

export const ImageGalleryExtension = Node.create<ExtensionOptions>({
  name: 'imageGallery',

  group: 'block',

  atom: true,

  addAttributes() {
    return {
      images: {
        default: [] as GalleryImage[],
        parseHTML: (element: HTMLElement) => {
          // Try data-images attribute first (editor internal format)
          const data = element.getAttribute('data-images')
          if (data) {
            try {
              const parsed = JSON.parse(data)
              if (Array.isArray(parsed) && parsed.length > 0) return parsed
            } catch {
              // fall through
            }
          }
          // Extract from rendered inner HTML (img tags)
          // Only pick thumbnails (ig-thumb class) to avoid duplicating the main image
          const thumbs = element.querySelectorAll('img.ig-thumb')
          if (thumbs.length > 0) {
            return Array.from(thumbs).map((img) => ({
              url: img.getAttribute('src') || '',
              alt: img.getAttribute('alt') || '',
            })).filter((img) => img.url)
          }
          // Fallback: if no ig-thumb class, get all imgs and deduplicate by src
          const allImgs = element.querySelectorAll('img')
          if (allImgs.length > 0) {
            const seen = new Set<string>()
            return Array.from(allImgs)
              .map((img) => ({
                url: img.getAttribute('src') || '',
                alt: img.getAttribute('alt') || '',
              }))
              .filter((img) => {
                if (!img.url || seen.has(img.url)) return false
                seen.add(img.url)
                return true
              })
          }
          return []
        },
        renderHTML: (attributes: { images: GalleryImage[] }) => {
          return {
            'data-images': JSON.stringify(attributes.images || []),
          }
        },
      },
    }
  },

  parseHTML() {
    return [
      {
        tag: 'div[data-type="image-gallery"]',
      },
    ]
  },

  renderHTML({ node, HTMLAttributes }) {
    const images: GalleryImage[] = node?.attrs?.images || []
    if (images.length === 0) {
      return ['div', mergeAttributes(HTMLAttributes, { 'data-type': 'image-gallery' })]
    }

    const mainImg = images[0]
    const galleryId = `ig-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

    // Build thumbnail img specs
    const thumbSpecs = images.map((img, i) => [
      'img',
      {
        class: 'ig-thumb',
        src: img.url,
        alt: img.alt || '',
        'data-index': String(i),
        style:
          'width:72px;height:72px;object-fit:cover;border-radius:4px;cursor:pointer;border:2px solid ' +
          (i === 0 ? '#3b82f6' : 'transparent') +
          ';transition:border-color .2s;',
      },
    ])

    // Return ProseMirror render spec for frontend HTML serialization
    return [
      'div',
      mergeAttributes(HTMLAttributes, {
        'data-type': 'image-gallery',
        'data-images': JSON.stringify(images),
        id: galleryId,
        style:
          'border:1px solid #e5e7eb;border-radius:8px;overflow:hidden;margin:1rem 0;',
      }),
      [
        'img',
        {
          class: 'ig-main',
          src: mainImg.url,
          alt: mainImg.alt || '',
          style:
            'width:100%;max-height:500px;object-fit:contain;display:block;border-radius:8px 8px 0 0;background:#f3f4f6;',
        },
      ],
      [
        'div',
        {
          style:
            'display:flex;gap:6px;padding:8px;overflow-x:auto;background:#f9fafb;border-top:1px solid #e5e7eb;',
        },
        ...thumbSpecs,
      ],
    ]
  },

  addNodeView() {
    return VueNodeViewRenderer(ImageGalleryNode)
  },

  addCommands() {
    return {
      insertImageGallery:
        () =>
        ({ commands }: any) => {
          return commands.insertContent({
            type: this.name,
            attrs: { images: [] },
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
            priority: 50,
            component: markRaw(ToolboxItem),
            props: {
              editor,
              icon: markRaw(RiGalleryLine),
              title: '图片展示器',
              action: () => {
                ;(editor.chain().focus() as any).insertImageGallery().run()
              },
            },
          },
        ]
      },
      getCommandMenuItems() {
        return {
          priority: 120,
          icon: markRaw(RiGalleryLine),
          title: '图片展示器',
          keywords: ['image-gallery', 'tupian', 'zhanshiqi', 'photo', 'gallery'],
          command: ({ editor, range }: { editor: Editor; range: Range }) => {
            ;(editor.chain().focus() as any).deleteRange(range).insertImageGallery().run()
          },
        }
      },
    }
  },
})

export default ImageGalleryExtension

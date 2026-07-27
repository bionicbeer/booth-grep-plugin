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
          const data = element.getAttribute('data-images')
          if (data) {
            try {
              return JSON.parse(data)
            } catch {
              return []
            }
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

  renderHTML({ HTMLAttributes }) {
    return ['div', mergeAttributes(HTMLAttributes, { 'data-type': 'image-gallery' })]
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

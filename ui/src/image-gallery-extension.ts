/* eslint-disable @typescript-eslint/no-explicit-any */
import { Node, mergeAttributes } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import ImageGalleryNode from './components/ImageGalleryNode.vue'
import { markRaw } from 'vue'

interface GalleryImage {
  url: string
  alt: string
}

export const ImageGalleryExtension = Node.create({
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
      getToolboxItems({ editor }: any) {
        return {
          priority: 50,
          component: markRaw(ToolboxItemPlaceholder),
          props: {
            editor,
            icon: null,
            title: '图片展示器',
            description: '插入图片展示器',
            action: () => {
              editor.chain().focus().insertImageGallery().run()
            },
          },
        }
      },
      getCommandMenuItems() {
        return {
          priority: 120,
          icon: null,
          title: '图片展示器',
          keywords: ['image-gallery', 'tupian', 'zhanshiqi', 'photo', 'gallery'],
          command: ({ editor, range }: any) => {
            editor
              .chain()
              .focus()
              .deleteRange(range)
              .insertImageGallery()
              .run()
          },
        }
      },
    }
  },
})

// Placeholder component for toolbox item - Halo will render the props
const ToolboxItemPlaceholder = {
  name: 'ToolboxItemPlaceholder',
  props: ['editor', 'icon', 'title', 'description', 'action'],
  template: '<span></span>',
}

export default ImageGalleryExtension

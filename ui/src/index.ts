import { definePlugin } from '@halo-dev/ui-shared'
import { IconPlug } from '@halo-dev/components'
import { markRaw } from 'vue'
import { ImageGalleryExtension } from './image-gallery-extension'
import { FileDownloadExtension } from './file-download-extension'
import RiDownloadCloudLine from '~icons/ri/download-cloud-line'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/booth-grab',
        name: 'BoothGrab',
        component: () => import('./views/BoothGrabView.vue'),
        meta: {
          title: '从Booth抓取',
          searchable: true,
          menu: {
            name: '从Booth抓取',
            group: 'content',
            icon: markRaw(RiDownloadCloudLine),
            priority: 1,
          },
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/example',
        name: 'Example',
        component: () => import(/* webpackChunkName: "HomeView" */ './views/HomeView.vue'),
        meta: {
          title: '示例页面',
          searchable: true,
          menu: {
            name: '示例页面',
            group: '示例分组',
            icon: markRaw(IconPlug),
            priority: 0,
          },
        },
      },
    },
  ],
  extensionPoints: {
    'default:editor:extension:create': () => {
      return [ImageGalleryExtension, FileDownloadExtension]
    },
  },
})

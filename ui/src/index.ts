/*!
 * Booth Grep plugin for Halo
 * Copyright (C) 2026 bionicbeer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

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

<!--
  Booth Grep plugin for Halo
  Copyright (C) 2026 bionicbeer

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <http://www.gnu.org/licenses/>.
-->
<script setup lang="ts">
/*!
 * Booth Grep plugin for Halo
 * Copyright (C) 2026 bionicbeer
 * Licensed under GPL-3.0-or-later: http://www.gnu.org/licenses/
 */
import { ref, computed, watch } from 'vue'
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/vue-3'

interface GalleryImage {
  url: string
  alt: string
}

const props = defineProps<NodeViewProps>()

const images = computed<GalleryImage[]>(() => {
  return props.node.attrs.images || []
})

const selectedIndex = ref(0)

// Reset selected index when images change
watch(images, (newImages) => {
  if (selectedIndex.value >= newImages.length) {
    selectedIndex.value = Math.max(0, newImages.length - 1)
  }
})

const currentImage = computed(() => {
  if (images.value.length === 0) return null
  return images.value[Math.min(selectedIndex.value, images.value.length - 1)]
})

const showUrlInput = ref(false)
const newImageUrl = ref('')
const fileInputRef = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
let cachedPolicyName: string | null = null

async function getPolicyName(): Promise<string> {
  if (cachedPolicyName) return cachedPolicyName

  try {
    const response = await fetch('/apis/storage.halo.run/v1alpha1/policies', {
      credentials: 'include',
    })
    if (response.ok) {
      const data = await response.json()
      const items = data?.items || []
      if (items.length > 0) {
        cachedPolicyName = items[0].metadata?.name || 'default-policy'
        return cachedPolicyName!
      }
    }
  } catch (e) {
    console.error('Failed to fetch policies:', e)
  }
  return 'default-policy'
}

function selectImage(index: number) {
  selectedIndex.value = index
}

function updateImages(newImages: GalleryImage[]) {
  props.updateAttributes({ images: newImages })
}

function addImageByUrl() {
  const url = newImageUrl.value.trim()
  if (!url) return

  const newImages = [...images.value, { url, alt: '' }]
  updateImages(newImages)
  selectedIndex.value = newImages.length - 1
  newImageUrl.value = ''
  showUrlInput.value = false
}

function triggerFileUpload() {
  fileInputRef.value?.click()
}

async function handleFileSelect(event: Event) {
  const input = event.target as HTMLInputElement
  const files = input.files
  if (!files || files.length === 0) return

  uploading.value = true
  try {
    for (const file of Array.from(files)) {
      if (!file.type.startsWith('image/')) continue

      const policyName = await getPolicyName()
      const formData = new FormData()
      formData.append('file', file)
      formData.append('policyName', policyName)

      const response = await fetch('/apis/api.console.halo.run/v1alpha1/attachments/upload', {
        method: 'POST',
        body: formData,
        credentials: 'include',
      })

      if (!response.ok) {
        console.error('Upload failed:', response.status, await response.text())
        continue
      }

      const data = await response.json()
      const url = data?.status?.permalink || data?.spec?.url || data?.url || ''
      if (url) {
        const newImages = [...images.value, { url, alt: file.name }]
        updateImages(newImages)
        selectedIndex.value = newImages.length - 1
      }
    }
  } catch (e) {
    console.error('Upload failed:', e)
  } finally {
    uploading.value = false
    input.value = ''
  }
}

function removeImage(index: number) {
  const newImages = images.value.filter((_, i) => i !== index)
  updateImages(newImages)
  if (selectedIndex.value >= newImages.length) {
    selectedIndex.value = Math.max(0, newImages.length - 1)
  } else if (selectedIndex.value > index) {
    selectedIndex.value--
  }
}

function moveLeft(index: number) {
  if (index <= 0) return
  const newImages = [...images.value]
  ;[newImages[index - 1], newImages[index]] = [newImages[index], newImages[index - 1]]
  updateImages(newImages)
  selectedIndex.value = index - 1
}

function moveRight(index: number) {
  if (index >= images.value.length - 1) return
  const newImages = [...images.value]
  ;[newImages[index], newImages[index + 1]] = [newImages[index + 1], newImages[index]]
  updateImages(newImages)
  selectedIndex.value = index + 1
}
</script>

<template>
  <NodeViewWrapper class="image-gallery-node" data-gallery-node>
    <!-- Empty state -->
    <div v-if="images.length === 0" class="gallery-empty">
      <div class="gallery-empty-icon">
        <svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
          <circle cx="8.5" cy="8.5" r="1.5"/>
          <polyline points="21 15 16 10 5 21"/>
        </svg>
      </div>
      <p class="gallery-empty-text">图片展示器</p>
      <div class="gallery-empty-actions">
        <button class="gallery-btn gallery-btn-primary" @click="triggerFileUpload">
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
            <polyline points="17 8 12 3 7 8"/>
            <line x1="12" y1="3" x2="12" y2="15"/>
          </svg>
          上传图片
        </button>
        <button class="gallery-btn gallery-btn-secondary" @click="showUrlInput = !showUrlInput">
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/>
            <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>
          </svg>
          输入链接
        </button>
      </div>
      <div v-if="showUrlInput" class="gallery-url-input">
        <input
          v-model="newImageUrl"
          type="text"
          placeholder="请输入图片链接..."
          class="gallery-input"
          @keyup.enter="addImageByUrl"
        />
        <button class="gallery-btn gallery-btn-primary" @click="addImageByUrl">添加</button>
      </div>
    </div>

    <!-- Gallery with images -->
    <div v-else class="gallery-container">
      <!-- Main large image -->
      <div class="gallery-main">
        <img
          :src="currentImage?.url"
          :alt="currentImage?.alt || ''"
          class="gallery-main-image"
        />
        <button
          class="gallery-remove-btn"
          title="移除图片"
          @click="removeImage(selectedIndex)"
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="18" y1="6" x2="6" y2="18"/>
            <line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>

      <!-- Thumbnails -->
      <div class="gallery-thumbnails">
        <div
          v-for="(image, index) in images"
          :key="index"
          class="gallery-thumbnail-wrapper"
          :class="{ 'is-active': index === selectedIndex }"
          @click="selectImage(index)"
        >
          <img :src="image.url" :alt="image.alt || ''" class="gallery-thumbnail" />
          <div class="gallery-thumbnail-actions">
            <button
              v-if="index > 0"
              class="gallery-thumb-btn"
              title="左移"
              @click.stop="moveLeft(index)"
            >
              <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
            </button>
            <button
              v-if="index < images.length - 1"
              class="gallery-thumb-btn"
              title="右移"
              @click.stop="moveRight(index)"
            >
              <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
            </button>
            <button
              class="gallery-thumb-btn gallery-thumb-remove"
              title="删除"
              @click.stop="removeImage(index)"
            >
              <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
          </div>
        </div>

        <!-- Add more buttons -->
        <div class="gallery-thumbnail-wrapper gallery-add-wrapper">
          <button class="gallery-add-btn" title="上传图片" @click="triggerFileUpload">
            <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="12" y1="5" x2="12" y2="19"/>
              <line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
          </button>
          <button class="gallery-add-btn gallery-add-url-btn" title="输入链接" @click="showUrlInput = !showUrlInput">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/>
              <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>
            </svg>
          </button>
        </div>
      </div>

      <!-- URL input row -->
      <div v-if="showUrlInput" class="gallery-url-input">
        <input
          v-model="newImageUrl"
          type="text"
          placeholder="请输入图片链接..."
          class="gallery-input"
          @keyup.enter="addImageByUrl"
        />
        <button class="gallery-btn gallery-btn-primary" @click="addImageByUrl">添加</button>
        <button class="gallery-btn gallery-btn-secondary" @click="showUrlInput = false">取消</button>
      </div>
    </div>

    <!-- Hidden file input -->
    <input
      ref="fileInputRef"
      type="file"
      accept="image/*"
      multiple
      style="display: none"
      @change="handleFileSelect"
    />
  </NodeViewWrapper>
</template>

<style scoped lang="scss">
.image-gallery-node {
  margin: 1rem 0;
}

.gallery-empty {
  border: 2px dashed #d1d5db;
  border-radius: 8px;
  padding: 2rem;
  text-align: center;
  background: #f9fafb;
  transition: border-color 0.2s;

  &:hover {
    border-color: #9ca3af;
  }
}

.gallery-empty-icon {
  color: #9ca3af;
  margin-bottom: 0.5rem;
}

.gallery-empty-text {
  color: #6b7280;
  font-size: 0.875rem;
  margin: 0.5rem 0 1rem;
}

.gallery-empty-actions {
  display: flex;
  gap: 0.5rem;
  justify-content: center;
}

.gallery-container {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}

.gallery-main {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  background: #f3f4f6;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.gallery-main-image {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.gallery-remove-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;

  &:hover {
    background: rgba(220, 38, 38, 0.8);
  }
}

.gallery-thumbnails {
  display: flex;
  gap: 6px;
  padding: 8px;
  overflow-x: auto;
  background: #f9fafb;
  border-top: 1px solid #e5e7eb;

  &::-webkit-scrollbar {
    height: 4px;
  }

  &::-webkit-scrollbar-thumb {
    background: #d1d5db;
    border-radius: 2px;
  }
}

.gallery-thumbnail-wrapper {
  position: relative;
  flex-shrink: 0;
  width: 64px;
  height: 64px;
  border-radius: 6px;
  overflow: hidden;
  cursor: pointer;
  border: 2px solid transparent;
  transition: border-color 0.2s;

  &.is-active {
    border-color: #3b82f6;
  }

  &:hover {
    border-color: #93c5fd;
  }

  &:hover .gallery-thumbnail-actions {
    opacity: 1;
  }
}

.gallery-thumbnail {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.gallery-thumbnail-actions {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.2s;
}

.gallery-thumb-btn {
  width: 20px;
  height: 20px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.2);
  color: #fff;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;

  &:hover {
    background: rgba(255, 255, 255, 0.4);
  }
}

.gallery-thumb-remove:hover {
  background: rgba(220, 38, 38, 0.7);
}

.gallery-add-wrapper {
  display: flex;
  gap: 4px;
  border: none;
  background: transparent;
  cursor: default;

  &:hover {
    border-color: transparent;
  }
}

.gallery-add-btn {
  width: 30px;
  height: 30px;
  border-radius: 6px;
  border: 1px dashed #d1d5db;
  background: #fff;
  color: #6b7280;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
  flex-shrink: 0;
  align-self: center;

  &:hover {
    border-color: #3b82f6;
    color: #3b82f6;
    background: #eff6ff;
  }
}

.gallery-add-url-btn {
  width: 30px;
  height: 30px;
}

.gallery-url-input {
  display: flex;
  gap: 0.5rem;
  padding: 8px;
  border-top: 1px solid #e5e7eb;
  background: #f9fafb;
  align-items: center;
}

.gallery-input {
  flex: 1;
  padding: 0.375rem 0.75rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 0.875rem;
  outline: none;
  transition: border-color 0.2s;

  &:focus {
    border-color: #3b82f6;
    box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.1);
  }
}

.gallery-btn {
  padding: 0.375rem 0.75rem;
  border-radius: 6px;
  font-size: 0.875rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 0.25rem;
  border: none;
  transition: all 0.2s;
  white-space: nowrap;
}

.gallery-btn-primary {
  background: #3b82f6;
  color: #fff;

  &:hover {
    background: #2563eb;
  }
}

.gallery-btn-secondary {
  background: #fff;
  color: #374151;
  border: 1px solid #d1d5db;

  &:hover {
    background: #f3f4f6;
  }
}
</style>

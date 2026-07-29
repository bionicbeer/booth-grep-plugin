<script setup lang="ts">
import { ref, computed } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Toast } from '@halo-dev/components'

interface ScrapeResult {
  title: string
  description: string
  author: string
  images: string[]
  error?: string
}

const url = ref('')
const loading = ref(false)
const saving = ref(false)
const result = ref<ScrapeResult | null>(null)
const selectedImages = ref<Set<number>>(new Set())
const title = ref('')
const author = ref('')
const description = ref('')

const hasResult = computed(() => result.value && !result.value.error)
const hasError = computed(() => result.value?.error)

function toggleImage(index: number) {
  const newSet = new Set(selectedImages.value)
  if (newSet.has(index)) {
    newSet.delete(index)
  } else {
    newSet.add(index)
  }
  selectedImages.value = newSet
}

function selectAllImages() {
  if (!result.value) return
  if (selectedImages.value.size === result.value.images.length) {
    selectedImages.value = new Set()
  } else {
    selectedImages.value = new Set(result.value.images.map((_, i) => i))
  }
}

async function scrape() {
  const inputUrl = url.value.trim()
  if (!inputUrl) {
    Toast.warning('请输入 booth.pm 商品链接')
    return
  }
  if (!inputUrl.includes('booth.pm')) {
    Toast.warning('请输入有效的 booth.pm 链接')
    return
  }

  loading.value = true
  result.value = null
  selectedImages.value = new Set()

  try {
    const { data } = await axiosInstance.post<ScrapeResult>(
      '/apis/console.api.booth-grep.halo.run/v1alpha1/booth/scrape',
      { url: inputUrl },
    )
    result.value = data
    if (data.error) {
      Toast.error('抓取失败: ' + data.error)
      return
    }
    // Pre-fill editable fields
    title.value = data.title || ''
    author.value = data.author || ''
    description.value = data.description || ''
    // Select all images by default
    if (data.images.length > 0) {
      selectedImages.value = new Set(data.images.map((_, i) => i))
    }
    Toast.success('抓取成功')
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '抓取失败'
    Toast.error(msg)
    result.value = { title: '', description: '', author: '', images: [], error: msg }
  } finally {
    loading.value = false
  }
}

async function getPolicyName(): Promise<string> {
  try {
    const response = await fetch('/apis/storage.halo.run/v1alpha1/policies', {
      credentials: 'include',
    })
    if (response.ok) {
      const data = await response.json()
      const items = data?.items || []
      if (items.length > 0) {
        return items[0].metadata?.name || 'default-policy'
      }
    }
  } catch (e) {
    console.error('Failed to fetch policies:', e)
  }
  return 'default-policy'
}

async function uploadImageToAttachment(imageUrl: string, filename: string): Promise<string | null> {
  try {
    // Download image from proxy
    const response = await fetch(imageUrl, { credentials: 'include' })
    if (!response.ok) return null

    const blob = await response.blob()
    const file = new File([blob], filename, { type: blob.type || 'image/jpeg' })

    // Upload to Halo attachment system
    const policyName = await getPolicyName()
    const formData = new FormData()
    formData.append('file', file)
    formData.append('policyName', policyName)

    const uploadResponse = await fetch('/apis/api.console.halo.run/v1alpha1/attachments/upload', {
      method: 'POST',
      body: formData,
      credentials: 'include',
    })

    if (!uploadResponse.ok) {
      console.error('Upload failed:', uploadResponse.status)
      return null
    }

    const data = await uploadResponse.json()
    return data?.status?.permalink || data?.spec?.url || null
  } catch (e) {
    console.error('Failed to upload image:', e)
    return null
  }
}

function buildTiptapContent(): { type: string; content: any[] } {
  const imgs = Array.from(selectedImages.value)
    .map((i) => result.value!.images[i])
    .filter(Boolean)

  const content: any[] = []

  // Image gallery node (at the beginning)
  if (imgs.length > 0) {
    content.push({
      type: 'imageGallery',
      attrs: {
        images: imgs.map((url) => ({ url, alt: '' })),
      },
    })
  }

  // Author paragraph
  if (author.value) {
    content.push({
      type: 'paragraph',
      content: [
        { type: 'text', text: '作者：', marks: [{ type: 'bold' }] },
        { type: 'text', text: author.value },
      ],
    })
  }

  // Description paragraphs (split by newlines)
  if (description.value) {
    const lines = description.value.split('\n').filter((l) => l.trim())
    for (const line of lines) {
      content.push({
        type: 'paragraph',
        content: [{ type: 'text', text: line.trim() }],
      })
    }
  }

  // Source link
  if (url.value) {
    content.push({
      type: 'paragraph',
      content: [
        { type: 'text', text: '来源：', marks: [{ type: 'italic' }] },
        {
          type: 'text',
          text: url.value,
          marks: [{ type: 'link', attrs: { href: url.value, target: '_blank' } }],
        },
      ],
    })
  }

  return { type: 'doc', content }
}

function buildHtmlContent(uploadedUrls: string[]): string {
  const imgs = Array.from(selectedImages.value)
    .map((i) => result.value!.images[i])
    .filter(Boolean)

  let html = ''

  // Image gallery widget (at the beginning)
  if (imgs.length > 0) {
    const galleryUrls = uploadedUrls.length > 0 ? uploadedUrls : imgs
    const mainImg = galleryUrls[0]
    const galleryId = `ig-${Date.now()}`

    html += `<div data-type="image-gallery" data-images='${JSON.stringify(galleryUrls.map((url) => ({ url, alt: '' })))}' id="${galleryId}" style="border:1px solid #e5e7eb;border-radius:8px;overflow:hidden;margin:1rem 0;">`
    html += `<img class="ig-main" src="${mainImg}" alt="" style="width:100%;max-height:500px;object-fit:contain;display:block;border-radius:8px 8px 0 0;background:#f3f4f6;" />`
    html += `<div style="display:flex;gap:6px;padding:8px;overflow-x:auto;background:#f9fafb;border-top:1px solid #e5e7eb;">`
    galleryUrls.forEach((url, i) => {
      const borderColor = i === 0 ? '#3b82f6' : 'transparent'
      html += `<img class="ig-thumb" src="${url}" alt="" data-index="${i}" style="width:72px;height:72px;object-fit:cover;border-radius:4px;cursor:pointer;border:2px solid ${borderColor};transition:border-color .2s;" />`
    })
    html += `</div></div>`
  }

  // Author
  if (author.value) {
    html += `<p><strong>作者：</strong>${author.value}</p>`
  }

  // Description (split by newlines into paragraphs)
  if (description.value) {
    const lines = description.value.split('\n').filter((l) => l.trim())
    for (const line of lines) {
      html += `<p>${line.trim()}</p>`
    }
  }

  // Source link
  if (url.value) {
    html += `<p><em>来源：<a href="${url.value}" target="_blank" rel="noopener">${url.value}</a></em></p>`
  }

  return html
}

async function saveAsPost() {
  if (!title.value.trim()) {
    Toast.warning('请输入文章标题')
    return
  }

  saving.value = true
  try {
    // Upload selected images to attachment system
    const selectedImgUrls = Array.from(selectedImages.value)
      .map((i) => result.value!.images[i])
      .filter(Boolean)

    Toast.info(`正在上传 ${selectedImgUrls.length} 张图片...`)

    const uploadedUrls: string[] = []
    for (let i = 0; i < selectedImgUrls.length; i++) {
      const imgUrl = selectedImgUrls[i]
      const filename = `booth-${Date.now()}-${i}.jpg`
      const attachmentUrl = await uploadImageToAttachment(imgUrl, filename)
      if (attachmentUrl) {
        uploadedUrls.push(attachmentUrl)
      }
    }

    if (uploadedUrls.length === 0 && selectedImgUrls.length > 0) {
      Toast.error('图片上传失败')
      return
    }

    // Build Tiptap JSON content (for editor)
    const tiptapContent = buildTiptapContent()
    // Build HTML content (for frontend rendering)
    const htmlContent = buildHtmlContent(uploadedUrls)

    // Replace image URLs with uploaded attachment URLs in Tiptap content
    if (uploadedUrls.length > 0) {
      const galleryNode = tiptapContent.content.find((node: any) => node.type === 'imageGallery')
      if (galleryNode) {
        galleryNode.attrs.images = uploadedUrls.map((url) => ({ url, alt: '' }))
      }
    }

    const slug = title.value
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9\u4e00-\u9fa5]+/g, '-')
      .replace(/^-|-$/g, '') || `booth-${Date.now()}`

    const postPayload = {
      post: {
        apiVersion: 'content.halo.run/v1alpha1',
        kind: 'Post',
        metadata: {
          name: '',
          generateName: 'post-',
        },
        spec: {
          title: title.value.trim(),
          slug,
          deleted: false,
          publish: false,
          pinned: false,
          allowComment: true,
          visible: 'PUBLIC',
          priority: 0,
          excerpt: { autoGenerate: true },
          cover: uploadedUrls[0] || result.value?.images?.[0] || '',
        },
      },
      content: {
        raw: htmlContent,
        content: htmlContent,
        rawType: 'HTML',
      },
    }

    await axiosInstance.post('/apis/api.console.halo.run/v1alpha1/posts', postPayload)
    Toast.success('文章已保存为草稿')

    // Reset state
    url.value = ''
    result.value = null
    selectedImages.value = new Set()
    title.value = ''
    author.value = ''
    description.value = ''
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '保存失败'
    Toast.error(msg)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="booth-grab">
    <div class="booth-grab-header">
      <h1 class="booth-grab-title">从 Booth 抓取</h1>
      <p class="booth-grab-desc">输入 booth.pm 商品页面链接，自动抓取图片、作者信息与商品描述，存为新文章。</p>
    </div>

    <!-- URL Input -->
    <div class="booth-grab-input-section">
      <div class="booth-grab-input-row">
        <input
          v-model="url"
          type="text"
          class="booth-grab-input"
          placeholder="https://booth.pm/ja/items/..."
          @keyup.enter="scrape"
        />
        <button class="booth-grab-btn booth-grab-btn-primary" :disabled="loading" @click="scrape">
          <span v-if="loading" class="booth-grab-spinner" />
          {{ loading ? '抓取中...' : '抓取' }}
        </button>
      </div>
    </div>

    <!-- Error -->
    <div v-if="hasError" class="booth-grab-error">
      {{ result?.error }}
    </div>

    <!-- Result Preview -->
    <div v-if="hasResult" class="booth-grab-result">
      <!-- Editable Meta -->
      <div class="booth-grab-meta">
        <div class="booth-grab-field">
          <label class="booth-grab-label">标题</label>
          <input v-model="title" type="text" class="booth-grab-input" placeholder="文章标题" />
        </div>
        <div class="booth-grab-field">
          <label class="booth-grab-label">作者</label>
          <input v-model="author" type="text" class="booth-grab-input" placeholder="作者名称" />
        </div>
        <div class="booth-grab-field">
          <label class="booth-grab-label">描述</label>
          <textarea
            v-model="description"
            class="booth-grab-textarea"
            rows="4"
            placeholder="商品描述"
          />
        </div>
      </div>

      <!-- Images -->
      <div class="booth-grab-images-section">
        <div class="booth-grab-images-header">
          <label class="booth-grab-label">
            图片 ({{ selectedImages.size }}/{{ result?.images.length }})
          </label>
          <button class="booth-grab-btn booth-grab-btn-sm" @click="selectAllImages">
            {{ selectedImages.size === result?.images.length ? '取消全选' : '全选' }}
          </button>
        </div>
        <div class="booth-grab-images">
          <div
            v-for="(img, index) in result?.images || []"
            :key="index"
            class="booth-grab-image-item"
            :class="{ 'is-selected': selectedImages.has(index) }"
            @click="toggleImage(index)"
          >
            <img :src="img" alt="" class="booth-grab-image" loading="lazy" />
            <div v-if="selectedImages.has(index)" class="booth-grab-image-check">✓</div>
          </div>
        </div>
      </div>

      <!-- Save Button -->
      <div class="booth-grab-actions">
        <button
          class="booth-grab-btn booth-grab-btn-success"
          :disabled="saving || !title.trim()"
          @click="saveAsPost"
        >
          <span v-if="saving" class="booth-grab-spinner" />
          {{ saving ? '保存中...' : '存为文章草稿' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.booth-grab {
  max-width: 800px;
  margin: 0 auto;
  padding: 2rem 1rem;
}

.booth-grab-header {
  margin-bottom: 1.5rem;
}

.booth-grab-title {
  font-size: 1.5rem;
  font-weight: 700;
  margin: 0 0 0.5rem;
}

.booth-grab-desc {
  color: #6b7280;
  font-size: 0.875rem;
  margin: 0;
}

.booth-grab-input-section {
  margin-bottom: 1.5rem;
}

.booth-grab-input-row {
  display: flex;
  gap: 0.5rem;
}

.booth-grab-input {
  flex: 1;
  padding: 0.5rem 0.75rem;
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

.booth-grab-textarea {
  width: 100%;
  padding: 0.5rem 0.75rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 0.875rem;
  outline: none;
  resize: vertical;
  font-family: inherit;
  transition: border-color 0.2s;

  &:focus {
    border-color: #3b82f6;
    box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.1);
  }
}

.booth-grab-btn {
  padding: 0.5rem 1rem;
  border-radius: 6px;
  font-size: 0.875rem;
  cursor: pointer;
  border: none;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  white-space: nowrap;
  transition: all 0.2s;

  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
}

.booth-grab-btn-primary {
  background: #3b82f6;
  color: #fff;
  &:hover:not(:disabled) {
    background: #2563eb;
  }
}

.booth-grab-btn-sm {
  padding: 0.25rem 0.5rem;
  font-size: 0.75rem;
  background: #f3f4f6;
  color: #374151;
  border: 1px solid #d1d5db;
  &:hover {
    background: #e5e7eb;
  }
}

.booth-grab-btn-success {
  background: #10b981;
  color: #fff;
  padding: 0.625rem 1.5rem;
  font-size: 1rem;
  &:hover:not(:disabled) {
    background: #059669;
  }
}

.booth-grab-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.booth-grab-error {
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #dc2626;
  padding: 0.75rem 1rem;
  border-radius: 6px;
  font-size: 0.875rem;
  margin-bottom: 1rem;
}

.booth-grab-result {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
}

.booth-grab-meta {
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  border-bottom: 1px solid #e5e7eb;
}

.booth-grab-field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.booth-grab-label {
  font-size: 0.75rem;
  font-weight: 600;
  color: #6b7280;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.booth-grab-images-section {
  padding: 1rem;
}

.booth-grab-images-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.75rem;
}

.booth-grab-images {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 0.5rem;
}

.booth-grab-image-item {
  position: relative;
  aspect-ratio: 1;
  border-radius: 6px;
  overflow: hidden;
  cursor: pointer;
  border: 2px solid transparent;
  transition: all 0.2s;

  &:hover {
    border-color: #93c5fd;
  }

  &.is-selected {
    border-color: #3b82f6;
  }
}

.booth-grab-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.booth-grab-image-check {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 20px;
  height: 20px;
  background: #3b82f6;
  color: #fff;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: bold;
}

.booth-grab-actions {
  padding: 1rem;
  border-top: 1px solid #e5e7eb;
  display: flex;
  justify-content: flex-end;
}
</style>

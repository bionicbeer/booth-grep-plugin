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
import { ref, computed, onMounted } from 'vue'
import { axiosInstance, coreApiClient } from '@halo-dev/api-client'
import { Toast } from '@halo-dev/components'
import { buildFileDownloadHtml, formatFileSize, type DownloadFile } from '../utils'

interface ScrapeResult {
  title: string
  description: string
  author: string
  images: string[]
  categories?: string[]
  error?: string
}

interface Prompts {
  titlePrompt: string
  descriptionPrompt: string
}

interface HaloCategory {
  name: string
  displayName: string
  slug: string
}

const API_BASE = '/apis/console.api.booth-grep.halo.run/v1alpha1/booth'
const PROMPTS_STORAGE_KEY = 'booth-grep-prompts'

const defaultPrompts: Prompts = {
  titlePrompt: '请优化以下标题，使其更简洁、吸引人，适合中文博客文章：',
  descriptionPrompt: '你即将收到一段爬取自Booth.pm的商品描述，你需要完成以下任务并只返回修改后的文本：1. 去除爬取过程中意外混入的其他商品的标题和价格；2.认真阅读文本，并用中文按介绍、使用说明、更新记录、其他文中提到的内容来回复。',
}

const url = ref('')
const loading = ref(false)
const saving = ref(false)
const result = ref<ScrapeResult | null>(null)
const selectedImages = ref<Set<number>>(new Set())
const title = ref('')
const author = ref('')
const description = ref('')

// Settings state
const prompts = ref<Prompts>({ ...defaultPrompts })
const showSettings = ref(false)
const organizingTitle = ref(false)
const organizingDescription = ref(false)

// Custom UA state
const useCustomUA = ref(false)
const customUA = ref('')

// Category state
const haloCategories = ref<HaloCategory[]>([])
const selectedCategories = ref<Set<string>>(new Set())

// File upload state (dual storage: local / S3, chosen per policy)
interface StoragePolicy {
  name: string
  displayName: string
  template: string
  label: string
}

interface UploadedFile extends DownloadFile {
  policyLabel: string
}

const storagePolicies = ref<StoragePolicy[]>([])
const filePolicyName = ref('')
const uploadedFiles = ref<UploadedFile[]>([])
const fileUploading = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)
const uploadProgress = ref(0)
const uploadingFileName = ref('')

const selectedFilePolicy = computed(() => {
  return storagePolicies.value.find((p) => p.name === filePolicyName.value) || null
})

async function loadStoragePolicies() {
  try {
    const response = await fetch('/apis/storage.halo.run/v1alpha1/policies', {
      credentials: 'include',
    })
    if (!response.ok) return
    const data = await response.json()
    storagePolicies.value = (data?.items || [])
      .map((p: any) => {
        const template = p?.spec?.templateName || ''
        const displayName = p?.spec?.displayName || p?.metadata?.name || ''
        let label = displayName
        if (template === 'local') {
          label = `本地存储（${displayName}）`
        } else if (template.includes('s3')) {
          // plugin-s3 registers templates like "s3" / "s3os"
          label = `S3 对象存储（${displayName}）`
        }
        return {
          name: p?.metadata?.name || '',
          displayName,
          template,
          label,
        }
      })
      .filter((p: StoragePolicy) => p.name)
    // Prefer the local policy as default when available
    const localPolicy = storagePolicies.value.find((p) => p.template === 'local')
    if (!filePolicyName.value && storagePolicies.value.length > 0) {
      filePolicyName.value = (localPolicy || storagePolicies.value[0]).name
    }
  } catch (e) {
    console.error('Failed to load storage policies:', e)
  }
}

function triggerFileInput() {
  fileInputRef.value?.click()
}

// XHR-based upload: fetch cannot report upload progress events, XHR can.
// Note: withCredentials is NOT set — bucket CORS does not allow credentials,
// while same-origin Halo API requests carry session cookies automatically.
function xhrUpload(
  method: 'PUT' | 'POST',
  url: string,
  body: File | FormData,
  onProgress: (percent: number) => void,
): Promise<{ status: number; text: string }> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open(method, url)
    xhr.upload.addEventListener('progress', (event) => {
      if (event.lengthComputable && event.total > 0) {
        onProgress(Math.round((event.loaded / event.total) * 100))
      }
    })
    xhr.addEventListener('load', () => resolve({ status: xhr.status, text: xhr.responseText }))
    xhr.addEventListener('error', () => reject(new Error('网络错误，请求失败')))
    xhr.send(body)
  })
}

// Upload through the Halo server (default for local policies and S3 fallback)
async function uploadViaConsole(file: File): Promise<string> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('policyName', filePolicyName.value)
  const { status, text } = await xhrUpload(
    'POST',
    '/apis/api.console.halo.run/v1alpha1/attachments/upload',
    formData,
    (percent) => {
      uploadProgress.value = percent
    },
  )
  if (status < 200 || status >= 300) {
    throw new Error(`服务器上传失败（${status}）`)
  }
  const data = JSON.parse(text || '{}')
  const permalink = data?.status?.permalink || data?.spec?.url || ''
  if (!permalink) {
    throw new Error('无返回地址')
  }
  return permalink
}

// Scheme A: presigned PUT URL. The browser PUTs the file straight to the bucket;
// Content-Type is left unsigned on the server, so no Content-Type header is set here.
async function uploadViaPresignedPut(file: File): Promise<string> {
  const presignResponse = await fetch(`${API_BASE}/upload/presign`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({
      policyName: filePolicyName.value,
      fileName: file.name,
      size: file.size,
      mediaType: file.type || 'application/octet-stream',
    }),
  })
  if (!presignResponse.ok) {
    const err = await presignResponse.json().catch(() => ({}))
    throw new Error(err?.error || `预签名失败（${presignResponse.status}）`)
  }
  const presignData = await presignResponse.json()

  const putResult = await xhrUpload('PUT', presignData.uploadUrl, file, (percent) => {
    uploadProgress.value = percent
  })
  if (putResult.status < 200 || putResult.status >= 300) {
    throw new Error(
      `直传存储桶失败（${putResult.status}），请确认存储桶已配置 CORS`,
    )
  }

  const completeResponse = await fetch(`${API_BASE}/upload/complete`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({
      policyName: filePolicyName.value,
      objectKey: presignData.objectKey,
      fileName: file.name,
      size: file.size,
      mediaType: file.type || 'application/octet-stream',
    }),
  })
  if (!completeResponse.ok) {
    const err = await completeResponse.json().catch(() => ({}))
    throw new Error(err?.error || `登记附件失败（${completeResponse.status}）`)
  }
  const completeData = await completeResponse.json()
  if (!completeData?.permalink) {
    throw new Error('无返回地址')
  }
  return completeData.permalink
}

async function uploadFiles(files: File[]) {
  if (files.length === 0) return
  if (!filePolicyName.value) {
    Toast.warning('未找到可用的存储策略，无法上传')
    return
  }
  fileUploading.value = true
  const policy = selectedFilePolicy.value
  const policyLabel = policy?.label || filePolicyName.value
  const useS3Direct = !!policy && policy.template.includes('s3')
  let uploadedCount = 0
  try {
    for (const file of files) {
      try {
        uploadingFileName.value = file.name
        uploadProgress.value = 0
        let fileUrl = ''
        if (useS3Direct) {
          try {
            fileUrl = await uploadViaPresignedPut(file)
          } catch (e) {
            const message = e instanceof Error ? e.message : String(e)
            Toast.warning(`S3 直传失败（${message}），已改用服务器中转上传`)
            fileUrl = await uploadViaConsole(file)
          }
        } else {
          fileUrl = await uploadViaConsole(file)
        }
        uploadedFiles.value = [
          ...uploadedFiles.value,
          { url: fileUrl, name: file.name, size: file.size, policyLabel },
        ]
        uploadedCount++
      } catch (e) {
        console.error('Failed to upload file:', e)
        const message = e instanceof Error ? e.message : ''
        Toast.error(`上传失败：${file.name}${message ? `（${message}）` : ''}`)
      }
    }
    if (uploadedCount > 0) {
      Toast.success(`已上传 ${uploadedCount} 个文件`)
    }
  } finally {
    fileUploading.value = false
    uploadingFileName.value = ''
    uploadProgress.value = 0
  }
}

function handleFileSelect(event: Event) {
  const input = event.target as HTMLInputElement
  const files = input.files ? Array.from(input.files) : []
  input.value = ''
  void uploadFiles(files)
}

function handleFileDrop(event: DragEvent) {
  const files = event.dataTransfer?.files ? Array.from(event.dataTransfer.files) : []
  void uploadFiles(files)
}

function removeUploadedFile(index: number) {
  uploadedFiles.value = uploadedFiles.value.filter((_, i) => i !== index)
}

async function loadHaloCategories() {
  try {
    const { data } = await coreApiClient.content.category.listCategory({ page: 0, size: 1000 })
    haloCategories.value = (data.items || [])
      .map((c) => ({
        name: c.metadata?.name || '',
        displayName: c.spec?.displayName || c.metadata?.name || '',
        slug: c.spec?.slug || '',
      }))
      .filter((c) => c.name)
      .sort((a, b) => a.displayName.localeCompare(b.displayName, 'zh-CN'))
  } catch (e) {
    console.error('Failed to load categories:', e)
  }
}

function normalizeCategoryText(s: string): string {
  let text = s
  try {
    text = decodeURIComponent(text)
  } catch {
    // keep original text
  }
  // Keep letters, digits and CJK only, so "Motion%26Animation", "Motion & Animation",
  // "motion animation" all normalize to "motionanimation"
  return text.toLowerCase().replace(/[^a-z0-9\u4e00-\u9fff]/g, '')
}

// Booth category -> Halo category slug mapping (both sides pre-normalized).
// Source: user-provided correspondence, e.g. "3D Characters" -> /categories/Avatars.
const BOOTH_TO_HALO_SLUG: Record<string, string> = {
  '3dcharacters': 'avatars',
  '3dclothing': 'clothing',
  '3dhair': 'hair',
  '3daccessories': 'accessories',
  '3dshoes': 'shoes',
  '3dprops': 'props',
  '3dtextures': 'textures',
  '3dmotionanimation': 'motionanimation',
  '3denvironmentsworld': 'environmentsworld',
  '3denvironmentsworlds': 'environmentsworld',
  vroid: 'vroid',
  '3dmodelsother': 'othermodels',
}

// Match Booth category names against Halo categories.
// 1. Explicit mapping table above, e.g. "3D Characters" -> slug "Avatars".
// 2. Fallback for unmapped names: full name or name with the leading token
//    (e.g. "3D") dropped, matched against slug / displayName / name,
//    e.g. "3D Tools & Systems" -> "Tools & Systems" matches slug "Tools&Systems".
// The generic parent "3D Models" is skipped to avoid noise.
function autoMatchCategories(boothCategories: string[]): string[] {
  const matched: string[] = []
  for (const bc of boothCategories) {
    const key = normalizeCategoryText(bc)
    if (key === '3dmodels') continue
    const mappedKey = BOOTH_TO_HALO_SLUG[key]
    let candidateKeys: string[]
    if (mappedKey) {
      candidateKeys = [mappedKey]
    } else {
      candidateKeys = [key]
      const tokens = bc.trim().split(/\s+/)
      if (tokens.length > 1) {
        candidateKeys.push(normalizeCategoryText(tokens.slice(1).join(' ')))
      }
    }
    for (const hc of haloCategories.value) {
      if (matched.includes(hc.name)) continue
      const keys = [hc.slug, hc.displayName, hc.name].filter(Boolean).map(normalizeCategoryText)
      if (candidateKeys.some((c) => keys.includes(c))) {
        matched.push(hc.name)
      }
    }
  }
  return matched
}

function toggleCategory(name: string) {
  const newSet = new Set(selectedCategories.value)
  if (newSet.has(name)) {
    newSet.delete(name)
  } else {
    newSet.add(name)
  }
  selectedCategories.value = newSet
}

// Load prompts: localStorage first, then fallback to plugin defaults from backend
onMounted(async () => {
  // Detect browser UA
  customUA.value = navigator.userAgent

  // Load all Halo categories for the selector
  loadHaloCategories()

  // Load storage policies (local / S3) for the file upload control
  loadStoragePolicies()

  // Check localStorage first
  const saved = localStorage.getItem(PROMPTS_STORAGE_KEY)
  if (saved) {
    try {
      prompts.value = { ...defaultPrompts, ...JSON.parse(saved) }
      return
    } catch (e) {
      console.error('Failed to parse saved prompts:', e)
    }
  }

  // If localStorage is empty, load defaults from backend (but don't save)
  try {
    const { data } = await axiosInstance.get(`${API_BASE}/ai/defaults`)
    prompts.value = {
      titlePrompt: data.titlePrompt || defaultPrompts.titlePrompt,
      descriptionPrompt: data.descriptionPrompt || defaultPrompts.descriptionPrompt,
    }
  } catch (e) {
    console.error('Failed to load default prompts:', e)
  }
})

function saveSettings() {
  localStorage.setItem(PROMPTS_STORAGE_KEY, JSON.stringify(prompts.value))
  Toast.success('提示词已保存')
  showSettings.value = false
}

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

async function callOrganizeApi(content: string, prompt: string): Promise<string> {
  const { data } = await axiosInstance.post<{ result?: string; error?: string }>(
    `${API_BASE}/ai/organize`,
    { content, prompt },
  )
  if (data.error) {
    throw new Error(data.error)
  }
  return data.result || content
}

async function organizeTitle() {
  if (!title.value.trim()) {
    Toast.warning('标题为空')
    return
  }
  organizingTitle.value = true
  try {
    const result = await callOrganizeApi(title.value, prompts.value.titlePrompt)
    title.value = result
    Toast.success('标题已整理')
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '整理失败'
    Toast.error(msg)
  } finally {
    organizingTitle.value = false
  }
}

async function organizeDescription() {
  if (!description.value.trim()) {
    Toast.warning('描述为空')
    return
  }
  organizingDescription.value = true
  try {
    const result = await callOrganizeApi(description.value, prompts.value.descriptionPrompt)
    description.value = result
    Toast.success('描述已整理')
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '整理失败'
    Toast.error(msg)
  } finally {
    organizingDescription.value = false
  }
}

async function scrape() {
  const inputUrl = url.value.trim()
  if (!inputUrl) {
    Toast.warning('请输入 Booth 商品链接或商品 ID')
    return
  }
  if (!inputUrl.includes('booth.pm') && !/^\d+$/.test(inputUrl)) {
    Toast.warning('请输入有效的 booth.pm 链接或商品 ID')
    return
  }

  loading.value = true
  result.value = null
  selectedImages.value = new Set()

  try {
    const payload: Record<string, string> = { url: inputUrl }
    if (useCustomUA.value && customUA.value.trim()) {
      payload.userAgent = customUA.value.trim()
    }
    const { data } = await axiosInstance.post<ScrapeResult>(
      '/apis/console.api.booth-grep.halo.run/v1alpha1/booth/scrape',
      payload,
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
    // Auto-match categories from the Booth product category path
    const matched = autoMatchCategories(data.categories || [])
    selectedCategories.value = new Set(matched)
    if (matched.length > 0) {
      Toast.success(`抓取成功，已自动匹配 ${matched.length} 个分类`)
    } else {
      Toast.success('抓取成功')
    }
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

  // File download cards (uploaded attachments)
  for (const file of uploadedFiles.value) {
    content.push({
      type: 'fileDownload',
      attrs: { url: file.url, name: file.name, size: file.size },
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

    // Self-contained thumbnail click handler (single quotes only, safe inside a
    // double-quoted attribute), so the gallery works on any theme without
    // theme-side scripts.
    const thumbOnClick =
      "var g=this.closest('[data-type=image-gallery]');if(!g)return;" +
      "var m=g.querySelector('.ig-main');if(!m)return;" +
      "m.srcset='';m.src='';m.src=this.src;m.alt=this.alt||'';" +
      "g.querySelectorAll('.ig-thumb').forEach(function(t){t.style.borderColor='transparent'});" +
      "this.style.borderColor='#3b82f6'"

    html += `<div data-type="image-gallery" data-images='${JSON.stringify(galleryUrls.map((url) => ({ url, alt: '' })))}' id="${galleryId}" style="border:1px solid #e5e7eb;border-radius:8px;overflow:hidden;margin:1rem 0;">`
    html += `<img class="ig-main" src="${mainImg}" alt="" style="width:100%;max-height:500px;object-fit:contain;display:block;border-radius:8px 8px 0 0;background:#f3f4f6;" />`
    html += `<div style="display:flex;gap:6px;padding:8px;overflow-x:auto;background:#f9fafb;border-top:1px solid #e5e7eb;">`
    galleryUrls.forEach((url, i) => {
      const borderColor = i === 0 ? '#3b82f6' : 'transparent'
      html += `<img class="ig-thumb" src="${url}" alt="" data-index="${i}" onclick="${thumbOnClick}" style="width:72px;height:72px;object-fit:cover;border-radius:4px;cursor:pointer;border:2px solid ${borderColor};transition:border-color .2s;" />`
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

  // File download cards (uploaded attachments)
  if (uploadedFiles.value.length > 0) {
    html += `<p><strong>下载：</strong></p>`
    for (const file of uploadedFiles.value) {
      html += buildFileDownloadHtml(file)
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
          categories: [...selectedCategories.value],
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
    selectedCategories.value = new Set()
    uploadedFiles.value = []
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
      <p class="booth-grab-desc">输入 Booth 商品页面链接或商品 ID，自动抓取图片、作者信息与商品描述，存为新文章。</p>
    </div>

    <!-- URL Input -->
    <div class="booth-grab-input-section">
      <div class="booth-grab-input-row">
        <input
          v-model="url"
          type="text"
          class="booth-grab-input"
          placeholder="https://booth.pm/ja/items/... 或商品 ID"
          @keyup.enter="scrape"
        />
        <button class="booth-grab-btn booth-grab-btn-primary" :disabled="loading" @click="scrape">
          <span v-if="loading" class="booth-grab-spinner" />
          {{ loading ? '抓取中...' : '抓取' }}
        </button>
      </div>
      <div class="booth-grab-ua-section">
        <label class="booth-grab-ua-checkbox">
          <input v-model="useCustomUA" type="checkbox" />
          <span>自定义 UA</span>
        </label>
        <input
          v-if="useCustomUA"
          v-model="customUA"
          type="text"
          class="booth-grab-input booth-grab-ua-input"
          placeholder="User-Agent"
        />
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
          <div class="booth-grab-field-header">
            <label class="booth-grab-label">标题</label>
            <button
              class="booth-grab-btn booth-grab-btn-sm booth-grab-btn-organize"
              :disabled="organizingTitle || !title.trim()"
              @click="organizeTitle"
            >
              <span v-if="organizingTitle" class="booth-grab-spinner booth-grab-spinner-sm" />
              {{ organizingTitle ? '整理中...' : '整理' }}
            </button>
          </div>
          <input v-model="title" type="text" class="booth-grab-input" placeholder="文章标题" />
        </div>
        <div class="booth-grab-field">
          <label class="booth-grab-label">作者</label>
          <input v-model="author" type="text" class="booth-grab-input" placeholder="作者名称" />
        </div>
        <div class="booth-grab-field">
          <div class="booth-grab-field-header">
            <label class="booth-grab-label">
              分类 ({{ selectedCategories.size }})
            </label>
            <span
              v-if="result?.categories && result.categories.length"
              class="booth-grab-category-source"
            >
              Booth 分类：{{ result.categories.join(' > ') }}
            </span>
          </div>
          <div v-if="haloCategories.length" class="booth-grab-categories">
            <label
              v-for="cat in haloCategories"
              :key="cat.name"
              class="booth-grab-category-item"
              :class="{ 'is-selected': selectedCategories.has(cat.name) }"
            >
              <input
                type="checkbox"
                :checked="selectedCategories.has(cat.name)"
                @change="toggleCategory(cat.name)"
              />
              <span>{{ cat.displayName }}</span>
            </label>
          </div>
          <p v-else class="booth-grab-category-empty">暂无分类，请先到 文章 &gt; 分类 中创建</p>
        </div>
        <div class="booth-grab-field">
          <div class="booth-grab-field-header">
            <label class="booth-grab-label">下载文件 ({{ uploadedFiles.length }})</label>
            <select v-model="filePolicyName" class="booth-grab-select" title="选择存储位置">
              <option v-for="p in storagePolicies" :key="p.name" :value="p.name">
                {{ p.label }}
              </option>
            </select>
          </div>
          <div
            class="booth-grab-file-dropzone"
            :class="{ 'is-uploading': fileUploading }"
            @click="triggerFileInput"
            @dragover.prevent
            @drop.prevent="handleFileDrop"
          >
            <input
              ref="fileInputRef"
              type="file"
              multiple
              hidden
              @change="handleFileSelect"
            />
            <template v-if="fileUploading">
              <div class="booth-grab-upload-progress">
                <div class="booth-grab-upload-progress-label">
                  <span class="booth-grab-spinner" />
                  上传中 {{ uploadingFileName }} {{ uploadProgress }}%
                </div>
                <div class="booth-grab-progress-track">
                  <div class="booth-grab-progress-bar" :style="{ width: uploadProgress + '%' }" />
                </div>
              </div>
            </template>
            <template v-else>
              点击或拖拽文件到此处上传，保存文章时将以下载卡片形式写入正文
            </template>
          </div>
          <div v-if="uploadedFiles.length" class="booth-grab-file-list">
            <div
              v-for="(file, index) in uploadedFiles"
              :key="file.url"
              class="booth-grab-file-item"
            >
              <span class="booth-grab-file-icon">↓</span>
              <div class="booth-grab-file-info">
                <span class="booth-grab-file-name" :title="file.url">{{ file.name }}</span>
                <span class="booth-grab-file-meta">
                  {{ formatFileSize(file.size) || '大小未知' }} · {{ file.policyLabel }}
                </span>
              </div>
              <button
                class="booth-grab-btn booth-grab-btn-sm"
                type="button"
                title="移除"
                @click="removeUploadedFile(index)"
              >
                移除
              </button>
            </div>
          </div>
          <p v-if="!storagePolicies.length" class="booth-grab-category-empty">
            未找到存储策略，请先到 附件 &gt; 存储策略 中配置（本地或 S3）
          </p>
        </div>
        <div class="booth-grab-field">
          <div class="booth-grab-field-header">
            <label class="booth-grab-label">描述</label>
            <button
              class="booth-grab-btn booth-grab-btn-sm booth-grab-btn-organize"
              :disabled="organizingDescription || !description.trim()"
              @click="organizeDescription"
            >
              <span v-if="organizingDescription" class="booth-grab-spinner booth-grab-spinner-sm" />
              {{ organizingDescription ? '整理中...' : '整理' }}
            </button>
          </div>
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
          class="booth-grab-btn booth-grab-btn-secondary"
          @click="showSettings = !showSettings"
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="3"/>
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/>
          </svg>
          设置
        </button>
        <button
          class="booth-grab-btn booth-grab-btn-success"
          :disabled="saving || !title.trim()"
          @click="saveAsPost"
        >
          <span v-if="saving" class="booth-grab-spinner" />
          {{ saving ? '保存中...' : '存为文章草稿' }}
        </button>
      </div>

      <!-- Settings Panel -->
      <div v-if="showSettings" class="booth-grab-settings">
        <h3 class="booth-grab-settings-title">整理提示词设置</h3>
        <p class="booth-grab-settings-desc">自定义整理提示词，保存后将在本地生效。如需恢复默认值，请清空后保存。</p>
        <div class="booth-grab-settings-field">
          <label class="booth-grab-label">标题整理提示词</label>
          <textarea
            v-model="prompts.titlePrompt"
            class="booth-grab-textarea"
            rows="2"
            placeholder="请输入标题整理提示词"
          />
        </div>
        <div class="booth-grab-settings-field">
          <label class="booth-grab-label">描述整理提示词</label>
          <textarea
            v-model="prompts.descriptionPrompt"
            class="booth-grab-textarea"
            rows="3"
            placeholder="请输入描述整理提示词"
          />
        </div>
        <div class="booth-grab-settings-actions">
          <button class="booth-grab-btn booth-grab-btn-secondary" @click="showSettings = false">
            取消
          </button>
          <button class="booth-grab-btn booth-grab-btn-primary" @click="saveSettings">
            保存设置
          </button>
        </div>
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

.booth-grab-ua-section {
  margin-top: 0.5rem;
}

.booth-grab-ua-checkbox {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.8125rem;
  color: #6b7280;
  cursor: pointer;
  user-select: none;

  input[type='checkbox'] {
    accent-color: #3b82f6;
    cursor: pointer;
  }
}

.booth-grab-ua-input {
  margin-top: 0.375rem;
  font-size: 0.75rem;
  font-family: monospace;
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

.booth-grab-category-source {
  font-size: 0.75rem;
  color: #6b7280;
}

.booth-grab-categories {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.booth-grab-category-item {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.25rem 0.625rem;
  border: 1px solid #d1d5db;
  border-radius: 9999px;
  font-size: 0.8125rem;
  color: #374151;
  cursor: pointer;
  user-select: none;
  transition:
    border-color 0.2s,
    background-color 0.2s,
    color 0.2s;

  input[type='checkbox'] {
    accent-color: #3b82f6;
    cursor: pointer;
  }

  &.is-selected {
    border-color: #3b82f6;
    background-color: rgba(59, 130, 246, 0.08);
    color: #2563eb;
  }
}

.booth-grab-category-empty {
  font-size: 0.8125rem;
  color: #9ca3af;
  margin: 0;
}

.booth-grab-select {
  max-width: 240px;
  padding: 0.25rem 0.5rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 0.75rem;
  color: #374151;
  background: #fff;
  outline: none;
  cursor: pointer;

  &:focus {
    border-color: #3b82f6;
    box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.1);
  }
}

.booth-grab-file-dropzone {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  padding: 1rem;
  border: 1px dashed #d1d5db;
  border-radius: 8px;
  background: #f9fafb;
  color: #6b7280;
  font-size: 0.8125rem;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.2s, background-color 0.2s;

  &:hover {
    border-color: #3b82f6;
    background: rgba(59, 130, 246, 0.04);
  }

  &.is-uploading {
    cursor: wait;
    opacity: 0.8;
  }
}

.booth-grab-upload-progress {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  width: 100%;
}

.booth-grab-upload-progress-label {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
}

.booth-grab-progress-track {
  height: 6px;
  overflow: hidden;
  border-radius: 3px;
  background: #e5e7eb;
}

.booth-grab-progress-bar {
  height: 100%;
  border-radius: 3px;
  background: #3b82f6;
  transition: width 0.2s ease;
}

.booth-grab-file-list {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  margin-top: 0.5rem;
}

.booth-grab-file-item {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
}

.booth-grab-file-icon {
  flex: 0 0 28px;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: #dbeafe;
  color: #2563eb;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.875rem;
}

.booth-grab-file-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.booth-grab-file-name {
  font-size: 0.8125rem;
  font-weight: 600;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.booth-grab-file-meta {
  font-size: 0.75rem;
  color: #6b7280;
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

.booth-grab-btn-organize {
  background: #fef3c7;
  color: #92400e;
  border-color: #fcd34d;
  &:hover:not(:disabled) {
    background: #fde68a;
  }
}

.booth-grab-btn-secondary {
  background: #fff;
  color: #374151;
  border: 1px solid #d1d5db;
  display: flex;
  align-items: center;
  gap: 0.375rem;
  &:hover:not(:disabled) {
    background: #f3f4f6;
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

.booth-grab-spinner-sm {
  width: 10px;
  height: 10px;
  border-width: 1.5px;
}

.booth-grab-field-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.25rem;
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
  justify-content: space-between;
  align-items: center;
  gap: 0.75rem;
}

.booth-grab-settings {
  padding: 1rem;
  border-top: 1px solid #e5e7eb;
  background: #f9fafb;
}

.booth-grab-settings-title {
  font-size: 1rem;
  font-weight: 600;
  margin: 0 0 0.5rem;
  color: #111827;
}

.booth-grab-settings-desc {
  font-size: 0.75rem;
  color: #6b7280;
  margin: 0 0 1rem;
}

.booth-grab-settings-field {
  margin-bottom: 1rem;
}

.booth-grab-settings-hint {
  font-size: 0.75rem;
  color: #6b7280;
  margin: 0.25rem 0 0;
}

.booth-grab-settings-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 1rem;
}
</style>

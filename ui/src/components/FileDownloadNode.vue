<script setup lang="ts">
import { computed, ref } from 'vue'
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/vue-3'
import RiDownload2Line from '~icons/ri/download-2-line'
import RiDeleteBinLine from '~icons/ri/delete-bin-line'
import RiPencilLine from '~icons/ri/pencil-line'
import { formatFileSize } from '../utils'

const props = defineProps<NodeViewProps>()

const url = computed(() => props.node.attrs.url || '')
const fileName = computed(() => props.node.attrs.name || '下载文件')
const sizeText = computed(() => formatFileSize(props.node.attrs.size || 0))

const editing = ref(!props.node.attrs.url)
const editUrl = ref(props.node.attrs.url || '')
const editName = ref(props.node.attrs.name || '')

function startEdit() {
  editUrl.value = props.node.attrs.url || ''
  editName.value = props.node.attrs.name || ''
  editing.value = true
}

function saveEdit() {
  const trimmedUrl = editUrl.value.trim()
  if (!trimmedUrl) return
  props.updateAttributes({
    url: trimmedUrl,
    name: editName.value.trim() || '下载文件',
  })
  editing.value = false
}

function cancelEdit() {
  if (!props.node.attrs.url) {
    // Inserted empty card without saving -> remove it
    props.deleteNode()
    return
  }
  editing.value = false
}
</script>

<template>
  <node-view-wrapper as="div" class="file-download-node">
    <div v-if="!editing" class="fd-card" :class="{ 'fd-selected': props.selected }">
      <div class="fd-icon">
        <RiDownload2Line />
      </div>
      <div class="fd-info">
        <div class="fd-name">{{ fileName }}</div>
        <div class="fd-size">{{ sizeText || '文件大小未知' }}</div>
      </div>
      <div class="fd-actions">
        <a
          v-if="url"
          class="fd-download"
          :href="url"
          target="_blank"
          rel="noopener"
          @click.stop
        >
          下载
        </a>
        <button class="fd-btn" type="button" title="编辑" @click.stop="startEdit">
          <RiPencilLine />
        </button>
        <button class="fd-btn fd-btn-danger" type="button" title="删除" @click.stop="props.deleteNode()">
          <RiDeleteBinLine />
        </button>
      </div>
    </div>
    <div v-else class="fd-edit" :class="{ 'fd-selected': props.selected }">
      <div class="fd-edit-title">文件下载卡片</div>
      <div class="fd-edit-field">
        <label class="fd-edit-label">下载地址</label>
        <input
          v-model="editUrl"
          class="fd-edit-input"
          type="text"
          placeholder="附件或外部文件 URL"
          @keyup.enter="saveEdit"
        />
      </div>
      <div class="fd-edit-field">
        <label class="fd-edit-label">文件名称</label>
        <input
          v-model="editName"
          class="fd-edit-input"
          type="text"
          placeholder="显示的文件名"
          @keyup.enter="saveEdit"
        />
      </div>
      <div class="fd-edit-actions">
        <button class="fd-btn" type="button" @click.stop="cancelEdit">取消</button>
        <button
          class="fd-btn fd-btn-primary"
          type="button"
          :disabled="!editUrl.trim()"
          @click.stop="saveEdit"
        >
          确定
        </button>
      </div>
    </div>
  </node-view-wrapper>
</template>

<style scoped lang="scss">
.file-download-node {
  margin: 0.5rem 0;
}

.fd-card,
.fd-edit {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
  transition: border-color 0.2s, box-shadow 0.2s;

  &.fd-selected {
    border-color: #3b82f6;
    box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.15);
  }
}

.fd-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
}

.fd-icon {
  flex: 0 0 36px;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: #dbeafe;
  color: #2563eb;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}

.fd-info {
  flex: 1;
  min-width: 0;
}

.fd-name {
  font-size: 0.875rem;
  font-weight: 600;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fd-size {
  font-size: 0.75rem;
  color: #6b7280;
}

.fd-actions {
  display: flex;
  align-items: center;
  gap: 0.375rem;
}

.fd-download {
  font-size: 0.875rem;
  color: #3b82f6;
  text-decoration: none;
  padding: 0.25rem 0.5rem;

  &:hover {
    color: #2563eb;
  }
}

.fd-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #6b7280;
  cursor: pointer;

  &:hover {
    background: #e5e7eb;
    color: #374151;
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.fd-btn-danger:hover {
  background: #fee2e2;
  color: #dc2626;
}

.fd-btn-primary {
  width: auto;
  padding: 0 0.75rem;
  background: #3b82f6;
  color: #fff;

  &:hover:not(:disabled) {
    background: #2563eb;
  }
}

.fd-edit {
  padding: 1rem;
}

.fd-edit-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: #111827;
  margin-bottom: 0.75rem;
}

.fd-edit-field {
  margin-bottom: 0.625rem;
}

.fd-edit-label {
  display: block;
  font-size: 0.75rem;
  font-weight: 600;
  color: #6b7280;
  margin-bottom: 0.25rem;
}

.fd-edit-input {
  width: 100%;
  padding: 0.5rem 0.75rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 0.875rem;
  outline: none;

  &:focus {
    border-color: #3b82f6;
    box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.1);
  }
}

.fd-edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 0.5rem;

  .fd-btn {
    width: auto;
    height: auto;
    padding: 0.375rem 0.75rem;
    font-size: 0.8125rem;
    border: 1px solid #d1d5db;
    background: #fff;
  }

  .fd-btn-primary {
    border: none;
  }
}
</style>

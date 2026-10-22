<script setup lang="ts" generic="T">
import type { DataTableColumn } from '@/types/ui'
import EmptyState from './EmptyState.vue'
import LoadingSpinner from './LoadingSpinner.vue'

const props = withDefaults(
  defineProps<{
    columns: DataTableColumn[]
    rows: T[]
    /** The name of the property that identifies a row. */
    rowKey: string
    loading?: boolean
    emptyTitle?: string
    emptyDescription?: string
    caption?: string
  }>(),
  {
    loading: false,
    emptyTitle: 'Nothing here yet',
    emptyDescription: undefined,
    caption: undefined,
  },
)

function cellValue(row: unknown, key: string): unknown {
  if (row && typeof row === 'object') {
    return (row as Record<string, unknown>)[key]
  }
  return undefined
}

function display(value: unknown): string {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value)
}

function keyOf(row: unknown, index: number): string {
  const key = cellValue(row, props.rowKey)
  return typeof key === 'string' || typeof key === 'number' ? String(key) : `row-${index}`
}
</script>

<template>
  <div class="table-wrap">
    <table class="data-table">
      <caption v-if="caption" class="sr-only">{{ caption }}</caption>
      <thead>
        <tr>
          <th
            v-for="column in columns"
            :key="column.key"
            :style="{ width: column.width, textAlign: column.align ?? 'left' }"
            scope="col"
          >
            {{ column.label }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="loading">
          <td :colspan="columns.length" class="table-loading">
            <LoadingSpinner label="Loading…" />
          </td>
        </tr>
        <tr v-else-if="rows.length === 0">
          <td :colspan="columns.length" class="table-empty">
            <EmptyState :title="emptyTitle" :description="emptyDescription">
              <slot name="empty" />
            </EmptyState>
          </td>
        </tr>
        <template v-else>
          <tr v-for="(row, index) in rows" :key="keyOf(row, index)">
            <td
              v-for="column in columns"
              :key="column.key"
              :data-label="column.label"
              :class="{ 'hide-mobile': column.hideOnMobile }"
              :style="{ textAlign: column.align ?? 'left' }"
            >
              <slot :name="`cell-${column.key}`" :row="row" :value="cellValue(row, column.key)">
                {{ display(cellValue(row, column.key)) }}
              </slot>
            </td>
          </tr>
        </template>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.table-loading,
.table-empty {
  padding: 0;
}

.hide-mobile {
  display: table-cell;
}

@media (max-width: 720px) {
  table.data-table thead {
    display: none;
  }

  table.data-table,
  table.data-table tbody,
  table.data-table tr,
  table.data-table td {
    display: block;
    width: 100%;
  }

  table.data-table tr {
    border: 1px solid var(--line);
    border-radius: var(--radius);
    margin-bottom: 10px;
    padding: 6px 4px;
    background: var(--surface);
  }

  table.data-table td {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    border-bottom: none;
    padding: 7px 10px;
    text-align: right !important;
  }

  table.data-table td::before {
    content: attr(data-label);
    font-size: 11.5px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.03em;
    color: var(--ink-500);
    text-align: left;
    flex: 0 0 auto;
  }

  .hide-mobile {
    display: none;
  }
}
</style>

/** The shared types of the user interface. */

/** One option of a select element. */
export interface SelectOption {
  value: string
  label: string
  disabled?: boolean
}

/** One column of the data table. */
export interface DataTableColumn {
  key: string
  label: string
  align?: 'left' | 'center' | 'right'
  width?: string
  /** The column is hidden on a narrow screen. */
  hideOnMobile?: boolean
}

import type { App } from 'vue'
import SmartElTable from './SmartElTable'
import SmartElTableColumn from './SmartElTableColumn'
import './smart-table.scss'

export function installSmartTable(app: App) {
  // Element Plus has already registered these names through app.use().  Calling
  // app.component() again works, but Vue emits a duplicate-registration warning
  // on every page load.  The smart table is an intentional application-level
  // replacement, so swap the two entries in the app component registry directly.
  const context = (app as unknown as {
    _context: { components: Record<string, unknown> }
  })._context
  context.components.ElTable = SmartElTable
  context.components.ElTableColumn = SmartElTableColumn
}

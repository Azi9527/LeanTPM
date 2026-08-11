const ACTIVE_STATUSES = new Set(['PENDING', 'IN_PROGRESS', 'OVERDUE'])
const STATUS_PRIORITY = Object.freeze({ OVERDUE: 0, IN_PROGRESS: 1, PENDING: 2 })

export function inspectionTaskListQuery(status = '') {
	if (status === 'PENDING') return { statusGroup: 'PENDING' }
	if (status === 'COMPLETED') {
		return { statusGroup: 'COMPLETED', sortBy: 'completedTime', sortDirection: 'DESC' }
	}
	return status ? { taskStatus: status } : {}
}

export function inspectionTodoRows(rows = [], limit = 100) {
	return (Array.isArray(rows) ? rows : [])
		.filter((task) => ACTIVE_STATUSES.has(task?.taskStatus))
		.sort((left, right) => {
			const statusOrder = (STATUS_PRIORITY[left.taskStatus] ?? 9) - (STATUS_PRIORITY[right.taskStatus] ?? 9)
			if (statusOrder) return statusOrder
			const leftDue = new Date(left.dueTime || '9999-12-31').getTime()
			const rightDue = new Date(right.dueTime || '9999-12-31').getTime()
			return leftDue - rightDue || Number(left.id || 0) - Number(right.id || 0)
		})
		.slice(0, Math.max(0, Number(limit) || 0))
}

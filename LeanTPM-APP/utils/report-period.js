function pad(value) {
	return String(value).padStart(2, '0')
}

export function formatLocalDate(value) {
	return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`
}

export function reportPeriodRange(period, referenceDate = new Date()) {
	const today = new Date(
		referenceDate.getFullYear(),
		referenceDate.getMonth(),
		referenceDate.getDate()
	)
	let start = new Date(today)
	let end = new Date(today)

	if (period === 'week') {
		const daysFromMonday = (today.getDay() + 6) % 7
		start.setDate(today.getDate() - daysFromMonday)
	} else if (period === 'previousMonth') {
		start = new Date(today.getFullYear(), today.getMonth() - 1, 1)
		end = new Date(today.getFullYear(), today.getMonth(), 0)
	} else if (period === 'today') {
		// The initialized start and end already point to today.
	} else {
		start = new Date(today.getFullYear(), today.getMonth(), 1)
	}

	return {
		startDate: formatLocalDate(start),
		endDate: formatLocalDate(end)
	}
}

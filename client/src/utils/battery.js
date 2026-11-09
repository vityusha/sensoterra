export const BATTERY_TYPES = [
  { value: 'ALKALINE', label: 'Alkaline', minVoltageV: 2.0, warningVoltageV: 2.5 },
  { value: 'NI_CD', label: 'Ni-Cd', minVoltageV: 1.8, warningVoltageV: 2.2 },
  { value: 'NI_MH', label: 'Ni-MH', minVoltageV: 1.8, warningVoltageV: 2.2 },
  { value: 'LI_ION', label: 'Li-ion', minVoltageV: 2.7, warningVoltageV: 2.9 },
]

export function batteryTypeInfo(type) {
  return BATTERY_TYPES.find(t => t.value === type) || BATTERY_TYPES[0]
}

export function batteryLabel(type) {
  return batteryTypeInfo(type).label
}

export function batteryMinVoltageV(type) {
  return batteryTypeInfo(type).minVoltageV
}

export function batteryWarningV(type) {
  return batteryTypeInfo(type).warningVoltageV
}

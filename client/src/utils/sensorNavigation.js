export function backFromNotifications(router, route, fieldKey) {
  if (window.history.state?.fromMeasurementPage) {
    router.back()
    return
  }
  const { chipId } = route.params
  const { deviceId } = route.query
  router.replace({
    name: 'sensor-measurement',
    params: { chipId, fieldKey },
    query: { deviceId },
  })
}

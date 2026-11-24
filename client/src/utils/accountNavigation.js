export function backFromAccountPage(router, target) {
  const previous = window.history.state?.back
  const hasReturnTarget = typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')

  if (previous && (!hasReturnTarget || previous === target)) {
    router.back()
  } else {
    router.replace(hasReturnTarget ? target : '/')
  }
}

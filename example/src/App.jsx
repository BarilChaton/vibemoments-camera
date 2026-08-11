import { useState } from 'react'
import { VibeCamera } from 'vibemoments-camera'

function App() {
  const [media, setMedia] = useState(null)

  function handleCapture(result) {
    console.log('CAPTURED:', result)
    setMedia(result)
  }

  function handleError(error) {
    console.error('CAMERA ERROR:', error)
  }

  return (
    <div>
      <VibeCamera onCapture={handleCapture} onError={handleError} />

      {media && <pre>{JSON.stringify(media, null, 2)}</pre>}
    </div>
  )
}

export default App

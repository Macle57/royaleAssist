import CoreImage
import CoreMedia
import ImageIO
import ReplayKit

/// The screen broadcast for royaleAssist (ReplayKit upload extension). It keeps only the newest
/// screen frame for the app: about two per second, upright, scaled to at most 1600 px on the long
/// side, written atomically as `capture/frame.jpg` in the shared App Group container, plus the
/// broadcast state in `capture/state.txt` ("started|paused|finished <epoch seconds>"). The app's
/// bubble reads that file when tapped (CaptureFrames.kt). Extensions get about 50 MB, so there is
/// no queue: frames that arrive while one is written are dropped.
final class SampleHandler: RPBroadcastSampleHandler {
    private static let appGroup = "group.com.macle.royaleassist"
    private static let interval: CFAbsoluteTime = 0.5
    private static let maxSide: CGFloat = 1600

    private let dir: URL? = {
        guard let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: SampleHandler.appGroup) else {
            return nil
        }
        let d = group.appendingPathComponent("capture", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
    private var lastWrite: CFAbsoluteTime = 0
    private var writing = false

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        writeState("started")
    }

    override func broadcastPaused() { writeState("paused") }

    override func broadcastResumed() { writeState("started") }

    override func broadcastFinished() { writeState("finished") }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video, let dir else { return }
        let now = CFAbsoluteTimeGetCurrent()
        guard !writing, now - lastWrite >= Self.interval, let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lastWrite = now
        writing = true
        defer { writing = false }
        autoreleasepool {
            var image = CIImage(cvPixelBuffer: pixels).oriented(Self.orientation(of: sampleBuffer))
            let extent = image.extent
            let scale = min(1, Self.maxSide / max(extent.width, extent.height))
            if scale < 1 {
                image = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            }
            image = image.transformed(by: CGAffineTransform(translationX: -image.extent.minX, y: -image.extent.minY))
            let options = [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): 0.85]
            guard let jpeg = context.jpegRepresentation(of: image, colorSpace: colorSpace, options: options) else { return }
            try? jpeg.write(to: dir.appendingPathComponent("frame.jpg"), options: .atomic)
        }
    }

    private func writeState(_ state: String) {
        guard let dir else { return }
        let text = "\(state) \(Date().timeIntervalSince1970)"
        try? text.write(to: dir.appendingPathComponent("state.txt"), atomically: true, encoding: .utf8)
    }

    /// How the frame has to be turned to be upright (ReplayKit sends the panel's native orientation).
    private static func orientation(of sampleBuffer: CMSampleBuffer) -> CGImagePropertyOrientation {
        guard let n = CMGetAttachment(sampleBuffer, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber,
              let o = CGImagePropertyOrientation(rawValue: n.uint32Value) else { return .up }
        return o
    }
}

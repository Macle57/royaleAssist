import AVFoundation
import AVKit
import ReplayKit
import SwiftUI
import UIKit
import Shared

/// The bubble on iOS. iOS has no windows over other apps, so the bubble is a picture-in-picture
/// window around an `AVSampleBufferDisplayLayer` that shows images the shared code draws
/// (`IosOverlay` / `PipRenderer` in :shared): the crown bubble, a spinner while scanning, then the
/// opponent's deck card. The window's play button is the tap on the bubble (scan), its skip
/// buttons step through the recent decks. The screen itself comes from the broadcast extension
/// (`royaleAssistBroadcast`), started with the system broadcast picker.
final class PipBubble: NSObject, IosScreenHost {
    static let shared = PipBubble()

    static let broadcastExtension = "com.macle.royaleassist.broadcast"

    /// Holds the display layer; it has to be in a window for picture in picture to start.
    let sourceView = PipSourceView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
    private let picker = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
    private var pip: AVPictureInPictureController?
    private var possibleObservation: NSKeyValueObservation?
    private var startWhenPossible = false
    private var lastImage: CGImage?
    private var keepAlive: Timer?

    private override init() {
        super.init()
        picker.preferredExtension = Self.broadcastExtension
        picker.showsMicrophoneButton = false
        sourceView.addSubview(picker)
    }

    // MARK: IosScreenHost

    func openBroadcastPicker() {
        // iOS starts a broadcast only from its own picker button; press it for the user.
        guard let button = Self.findButton(in: picker) else {
            NSLog("royaleassist bubble: no button in the broadcast picker")
            return
        }
        button.sendActions(for: .touchUpInside)
    }

    func startBubble() {
        guard AVPictureInPictureController.isPictureInPictureSupported() else {
            IosOverlay.shared.bubbleFailed(reason: "picture in picture is not supported here")
            return
        }
        activateAudioSession()
        // The layer needs a frame before picture in picture can start.
        if let image = IosOverlay.shared.currentImage() { showFrame(image: image) }
        let controller = pip ?? makeController()
        if controller.isPictureInPictureActive { return }
        if controller.isPictureInPicturePossible {
            controller.startPictureInPicture()
            reportIfNotStarted()
        } else {
            startWhenPossible = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
                guard let self, self.startWhenPossible else { return }
                self.startWhenPossible = false
                IosOverlay.shared.bubbleFailed(reason: "picture in picture did not become available")
            }
        }
    }

    func stopBubble() {
        pip?.stopPictureInPicture()
    }

    func isBubbleShown() -> Bool { pip?.isPictureInPictureActive ?? false }

    func isBubbleSupported() -> Bool { AVPictureInPictureController.isPictureInPictureSupported() }

    func showFrame(image: UIImage) {
        guard let cg = image.cgImage else { return }
        lastImage = cg
        enqueue(cg)
    }

    func playbackStateChanged() {
        pip?.invalidatePlaybackState()
    }

    // MARK: Internals

    private func makeController() -> AVPictureInPictureController {
        let source = AVPictureInPictureController.ContentSource(
            sampleBufferDisplayLayer: sourceView.displayLayer, playbackDelegate: self)
        let controller = AVPictureInPictureController(contentSource: source)
        controller.delegate = self
        // Skip buttons instead of a scrubber: they step through the decks.
        controller.requiresLinearPlayback = false
        possibleObservation = controller.observe(\.isPictureInPicturePossible, options: [.new]) { [weak self] c, _ in
            guard let self, c.isPictureInPicturePossible, self.startWhenPossible else { return }
            self.startWhenPossible = false
            DispatchQueue.main.async {
                c.startPictureInPicture()
                self.reportIfNotStarted()
            }
        }
        pip = controller
        return controller
    }

    /// Some refusals come without a delegate call (no user gesture, simulator): report those too.
    private func reportIfNotStarted() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
            guard let self, let pip = self.pip, !pip.isPictureInPictureActive, !self.startWhenPossible else { return }
            IosOverlay.shared.bubbleFailed(reason: "picture in picture did not start")
        }
    }

    /// Picture in picture needs a playback audio session; mixing keeps the game's sound playing.
    private func activateAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .moviePlayback, options: [.mixWithOthers])
            try session.setActive(true)
        } catch {
            NSLog("royaleassist bubble: audio session: \(error.localizedDescription)")
        }
    }

    private func enqueue(_ cg: CGImage) {
        guard let buffer = Self.sampleBuffer(from: cg) else { return }
        let renderer = sourceView.displayLayer.sampleBufferRenderer
        if renderer.status == .failed { renderer.flush() }
        renderer.enqueue(buffer)
    }

    /// Re-sends the current image every second while the window is up, so it never runs dry.
    private func setKeepAlive(_ on: Bool) {
        keepAlive?.invalidate()
        keepAlive = nil
        guard on else { return }
        keepAlive = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            guard let self, let cg = self.lastImage else { return }
            self.enqueue(cg)
        }
    }

    private static func findButton(in view: UIView) -> UIButton? {
        for sub in view.subviews {
            if let b = sub as? UIButton { return b }
            if let b = findButton(in: sub) { return b }
        }
        return nil
    }

    /// A BGRA pixel buffer with [cg], wrapped in a sample buffer that is shown at once.
    private static func sampleBuffer(from cg: CGImage) -> CMSampleBuffer? {
        let w = cg.width, h = cg.height
        let attrs: [CFString: Any] = [
            kCVPixelBufferIOSurfacePropertiesKey: [:] as [CFString: Any],
            kCVPixelBufferCGImageCompatibilityKey: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey: true,
        ]
        var pixelBuffer: CVPixelBuffer?
        guard CVPixelBufferCreate(nil, w, h, kCVPixelFormatType_32BGRA, attrs as CFDictionary, &pixelBuffer) == kCVReturnSuccess,
              let pb = pixelBuffer else { return nil }
        CVPixelBufferLockBaseAddress(pb, [])
        let ctx = CGContext(
            data: CVPixelBufferGetBaseAddress(pb), width: w, height: h, bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(pb), space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        ctx?.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        CVPixelBufferUnlockBaseAddress(pb, [])

        var format: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: pb, formatDescriptionOut: &format) == noErr,
              let format else { return nil }
        var timing = CMSampleTimingInfo(
            duration: .invalid, presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()), decodeTimeStamp: .invalid)
        var sampleBuffer: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: nil, imageBuffer: pb, formatDescription: format, sampleTiming: &timing, sampleBufferOut: &sampleBuffer) == noErr,
              let sb = sampleBuffer else { return nil }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: true), CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(
                dict,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sb
    }
}

extension PipBubble: AVPictureInPictureControllerDelegate {
    func pictureInPictureControllerDidStartPictureInPicture(_ c: AVPictureInPictureController) {
        setKeepAlive(true)
        IosOverlay.shared.bubbleShown(shown: true)
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ c: AVPictureInPictureController) {
        setKeepAlive(false)
        IosOverlay.shared.bubbleShown(shown: false)
    }

    func pictureInPictureController(_ c: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        IosOverlay.shared.bubbleFailed(reason: error.localizedDescription)
    }

    func pictureInPictureController(
        _ c: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void
    ) {
        completionHandler(true)
    }
}

extension PipBubble: AVPictureInPictureSampleBufferPlaybackDelegate {
    /// Play/pause = the tap on the bubble: read the screen and look the opponent up.
    func pictureInPictureController(_ c: AVPictureInPictureController, setPlaying playing: Bool) {
        IosOverlay.shared.onPlayTapped()
        c.invalidatePlaybackState()
    }

    /// A finite range, so the window shows skip buttons (a live stream would hide them).
    func pictureInPictureControllerTimeRangeForPlayback(_ c: AVPictureInPictureController) -> CMTimeRange {
        CMTimeRange(start: .zero, duration: CMTime(seconds: 24 * 3600, preferredTimescale: 1))
    }

    /// "Playing" while a scan runs; otherwise the play button invites the next scan.
    func pictureInPictureControllerIsPlaybackPaused(_ c: AVPictureInPictureController) -> Bool {
        !IosOverlay.shared.isBusy()
    }

    func pictureInPictureController(_ c: AVPictureInPictureController, didTransitionToRenderSize newRenderSize: CMVideoDimensions) {}

    /// Skip forward = an older deck, skip back = a newer one.
    func pictureInPictureController(
        _ c: AVPictureInPictureController, skipByInterval skipInterval: CMTime, completion completionHandler: @escaping () -> Void
    ) {
        IosOverlay.shared.onSkip(forward: skipInterval.seconds > 0)
        completionHandler()
    }
}

/// A view whose layer is the picture-in-picture source.
final class PipSourceView: UIView {
    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }

    var displayLayer: AVSampleBufferDisplayLayer {
        // swiftlint:disable:next force_cast
        layer as! AVSampleBufferDisplayLayer
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        displayLayer.videoGravity = .resizeAspect
        isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) { fatalError("not used") }
}

/// Puts the picture-in-picture source view (1 x 1 pt) into the SwiftUI window.
struct PipSourceHost: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView { PipBubble.shared.sourceView }
    func updateUIView(_ uiView: UIView, context: Context) {}
}

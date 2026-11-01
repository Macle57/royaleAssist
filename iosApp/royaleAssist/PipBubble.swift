import AVFoundation
import AVKit
import ReplayKit
import SwiftUI
import UIKit
import Shared

/// The bubble on iOS. iOS has no windows over other apps, so the bubble is a picture-in-picture
/// window around an `AVSampleBufferDisplayLayer` that shows images the shared code draws
/// (`IosOverlay` / `PipRenderer` in :shared): the crown bubble until the first scan, a spinner while
/// scanning, then the opponent's deck card, which stays until the next play press replaces it.
/// The window's play button is the tap on the bubble (scan), its skip buttons step through the
/// recent decks. The screen itself comes from the broadcast extension
/// (`royaleAssistBroadcast`), started with the system broadcast picker.
final class PipBubble: NSObject, IosScreenHost {
    static let shared = PipBubble()

    static let broadcastExtension = "com.macle.royaleassist.broadcast"

    /// Holds the display layer; it has to be in a window for picture in picture to start.
    let sourceView = PipSourceView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
    private let picker = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
    private var pip: AVPictureInPictureController?
    /// Between "will start" and "did start / failed".
    private var starting = false
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
        sourceView.pinClock()
        // The layer needs a frame before picture in picture can start.
        if let image = IosOverlay.shared.currentImage() { showFrame(image: image) }
        let controller = pip ?? makeController()
        if controller.isPictureInPictureActive || starting { return }
        tryStart(attempt: 0)
    }

    /// A new controller flips between "possible" and not for a moment after it is made, and a
    /// start in that gap fails without a delegate call; so retry for a few seconds.
    private func tryStart(attempt: Int) {
        guard let controller = pip, !controller.isPictureInPictureActive, !starting else { return }
        if controller.isPictureInPicturePossible { controller.startPictureInPicture() }
        guard attempt < 12 else {
            IosOverlay.shared.bubbleFailed(reason: "picture in picture did not start")
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.tryStart(attempt: attempt + 1) }
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
        // Skip buttons instead of a scrubber: they step through the decks. (Linear playback would
        // disable forward skip; nothing else in the API turns the skip buttons off.)
        controller.requiresLinearPlayback = false
        pip = controller
        return controller
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
        guard let buffer = Self.sampleBuffer(from: cg, at: sourceView.clockTime) else { return }
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

    /// A BGRA pixel buffer with [cg], wrapped in a sample buffer stamped [time] (the layer's pinned
    /// clock) and marked to be shown at once.
    private static func sampleBuffer(from cg: CGImage, at time: CMTime) -> CMSampleBuffer? {
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
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: time, decodeTimeStamp: .invalid)
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
    func pictureInPictureControllerWillStartPictureInPicture(_ c: AVPictureInPictureController) {
        starting = true
    }

    func pictureInPictureControllerDidStartPictureInPicture(_ c: AVPictureInPictureController) {
        starting = false
        setKeepAlive(true)
        IosOverlay.shared.bubbleShown(shown: true)
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ c: AVPictureInPictureController) {
        setKeepAlive(false)
        IosOverlay.shared.bubbleShown(shown: false)
    }

    func pictureInPictureController(_ c: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        starting = false
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

    /// A finite range, so the window shows skip buttons (a live stream would hide them). AVKit
    /// requires a finite range to contain the current time of the layer's timebase; the layer's
    /// clock is pinned in the middle of it ([PipSourceView.pinClock]), so the position is never at
    /// either end and both skip buttons stay enabled.
    func pictureInPictureControllerTimeRangeForPlayback(_ c: AVPictureInPictureController) -> CMTimeRange {
        CMTimeRange(start: .zero, duration: PipSourceView.clockRange)
    }

    /// "Playing" while a scan runs; otherwise the play button invites the next scan.
    func pictureInPictureControllerIsPlaybackPaused(_ c: AVPictureInPictureController) -> Bool {
        !IosOverlay.shared.isBusy()
    }

    func pictureInPictureController(_ c: AVPictureInPictureController, didTransitionToRenderSize newRenderSize: CMVideoDimensions) {}

    /// Skip forward = an older deck, skip back = a newer one. Works while paused (the normal state:
    /// paused means "ready to scan"). The clock does not move: it stays pinned mid-range, which the
    /// completion handler requires to be the current time when it runs.
    func pictureInPictureController(
        _ c: AVPictureInPictureController, skipByInterval skipInterval: CMTime, completion completionHandler: @escaping () -> Void
    ) {
        let forward = skipInterval.seconds > 0
        NSLog("royaleassist bubble: skip \(forward ? "forward" : "back") (\(skipInterval.seconds) s)")
        sourceView.pinClock()
        IosOverlay.shared.onSkip(forward: forward)
        completionHandler()
        c.invalidatePlaybackState()
    }
}

/// A view whose layer is the picture-in-picture source.
final class PipSourceView: UIView {
    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }

    var displayLayer: AVSampleBufferDisplayLayer {
        // swiftlint:disable:next force_cast
        layer as! AVSampleBufferDisplayLayer
    }

    /// Length of the playback range the window is told about; the clock sits at its middle.
    static let clockRange = CMTime(seconds: 24 * 3600, preferredTimescale: 600)
    static let clockPosition = CMTime(seconds: 12 * 3600, preferredTimescale: 600)

    /// The layer's clock, stopped (rate 0) at [clockPosition]. Without it the layer runs on the host
    /// clock (seconds since boot, often more than 24 h), so the window saw a position outside the
    /// playback range: at or past the end, where forward skip is disabled and skips go nowhere.
    private let clock: CMTimebase?

    override init(frame: CGRect) {
        var tb: CMTimebase?
        let status = CMTimebaseCreateWithSourceClock(allocator: kCFAllocatorDefault, sourceClock: CMClockGetHostTimeClock(), timebaseOut: &tb)
        if status != noErr { NSLog("royaleassist bubble: no timebase (\(status))") }
        clock = tb
        super.init(frame: frame)
        displayLayer.videoGravity = .resizeAspect
        displayLayer.controlTimebase = clock
        pinClock()
        isUserInteractionEnabled = false
    }

    /// Puts the clock back at [clockPosition], stopped.
    func pinClock() {
        guard let clock else { return }
        CMTimebaseSetRate(clock, rate: 0)
        CMTimebaseSetTime(clock, time: Self.clockPosition)
    }

    /// The time new frames are stamped with (the pinned clock), so they are on screen at once.
    var clockTime: CMTime { clock.map { CMTimebaseGetTime($0) } ?? Self.clockPosition }

    required init?(coder: NSCoder) { fatalError("not used") }
}

/// Puts the picture-in-picture source view (1 x 1 pt) into the SwiftUI window.
struct PipSourceHost: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView { PipBubble.shared.sourceView }
    func updateUIView(_ uiView: UIView, context: Context) {}
}

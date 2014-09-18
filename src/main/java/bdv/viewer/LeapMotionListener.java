package bdv.viewer;

import com.leapmotion.leap.*;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.ui.InteractiveDisplayCanvasComponent;

import javax.swing.*;

/**
 * Leap Motion imports
 */

/**
 * An extension of the Listener Class to support the Leap Motion
 * gesture input device.
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */

public class LeapMotionListener extends Listener
{
    protected final JPanel parentFrame;
    protected final InteractiveDisplayCanvasComponent< AffineTransform3D > display;

    private boolean debug = true;
    Frame previousLeapFrame = Frame.invalid();

    protected void debugPrint(String text) {
        if (this.debug) {
            System.out.println("LeapMotionListener> " + text);
        }
    }
    public LeapMotionListener(final JPanel frame, final InteractiveDisplayCanvasComponent< AffineTransform3D > display) {
        this.parentFrame = frame;
        this.display = display;
    }

    public void onInit(Controller controller) {
        debugPrint("Initialized controller.");
    }

    public void onConnect(Controller controller) {
        debugPrint("Leap Motion controller connected.");
        controller.enableGesture(Gesture.Type.TYPE_SWIPE);
    }

    public void onDisconnect(Controller controller) {
        //Note: not dispatched when running in a debugger.
        debugPrint("Leap Motion controller disconnected.");
    }

    public void onExit(Controller controller) {
        debugPrint("Exited.");
    }

    public void onFrame(Controller controller) {
        // get most recent Leap Motion frame
        Frame leapFrame = controller.frame();

        // affine transforms needed for translation and rotation
        AffineTransform3D origin = new AffineTransform3D();
        AffineTransform3D transform = new AffineTransform3D();

        // if first hand is closed, skip the frame to enable the user to
        // move the hand out of the control area without further movement
        if (leapFrame.fingers().count() < 1) {
            previousLeapFrame = Frame.invalid();
            return;
        }

        if (leapFrame.hands().count() == 1) {
            // get first hand from frame
            Hand hand = leapFrame.hands().get(0);

            double dX, dY, dZ;

            Vector handTranslation = hand.translation(previousLeapFrame);
            dX = handTranslation.getX();
            dY = handTranslation.getY();
            dZ = handTranslation.getZ();

            // discard frame if movement is too large
            if (Math.abs(dX) > 40.0 || Math.abs(dY) > 40.0 || Math.abs(dZ) > 40.0) {
                previousLeapFrame = Frame.invalid();
                return;
            }

            // initialize transformations
            origin.set(display.getTransformEventHandler().getTransform());
            transform.set(origin);

            // three fingers moved = translation
            if (hand.fingers().count() == 3) {
                transform.set(origin.get(0, 3) - (-2.0 * dX), 0, 3);
                transform.set(origin.get(1, 3) - 2.0 * dY, 1, 3);
                transform.set(origin.get(2, 3) - 2.0 * dZ, 2, 3);
            }

            // five fingers = rotation
            if (hand.fingers().count() == 5) {
                // for rotation, we first have to shift our origin of rotation
                // to the center of the window, then rotate, then shift back:

                // center shift
                transform.set(transform.get(0, 3) - parentFrame.getWidth() / 2., 0, 3);
                transform.set(transform.get(1, 3) - parentFrame.getHeight() / 2, 1, 3);

                // zoom / Z translation
                transform.set(transform.get(2, 3) - dZ, 2, 3);

                // transform - InteractiveDisplay3DCanvas' and Leap Motion's coordinate systems
                // are different!
                double xAngle = -dX * Math.PI / 180.0f;
                double yAngle = -dY * Math.PI / 180.0f;

                transform.rotate(0, yAngle);
                transform.rotate(1, xAngle);

                // center un-shift
                transform.set(transform.get(0, 3) + parentFrame.getWidth() / 2, 0, 3);
                transform.set(transform.get(1, 3) + parentFrame.getHeight() / 2, 1, 3);
            }

            display.getTransformEventHandler().setTransform(transform);
            display.transformChanged(transform);

            previousLeapFrame = leapFrame;

        }

        if (leapFrame.hands().count() == 2) {
            Hand hand1 = leapFrame.hands().get(0);
            Hand hand2 = leapFrame.hands().get(1);

            // rotate around picked point
            if(hand1.fingers().count() == 0 && hand2.fingers().count() == 5) {

            }
        }
    }

}

import java.awt.*;

public class Body {
    public final String name;
    public double x, y;
    public double xv, yv;
    public int r;
    public double mass;
    public Color color;

    public Body(String name) {
        this.name = name;
    }

    public double getX() {
        return x;
    }
    public double getY() {
        return y;
    }
    public double getXv() {
        return xv;
    }
    public double getYv() {
        return yv;
    }

    public void setX(double x) {
        this.x = x;
    }
    public void setY(double y) {
        this.y = y;
    }
}

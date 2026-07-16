package ca.venn.loadfunds.model.velocity;

public record Decision(boolean accepted, RejectionReason reason) {

    public static Decision accept() {
        return new Decision(true, null);
    }

    public static Decision decline(RejectionReason r) {
        return new Decision(false, r);
    }
}


package id.tensky.coldspot.feature

/** A plain class with a branch: the spike's subject for a library module. */
class Pricing {
    fun price(quantity: Int): Int {
        return if (quantity >= 10) {
            quantity * 9
        } else {
            quantity * 10
        }
    }
}

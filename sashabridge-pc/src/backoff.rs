//! Exponential backoff of the reconnection loops: the music session and the S Pen route.

use std::time::Duration;

/// Exponential backoff with an upper bound.
#[derive(Debug)]
pub struct Backoff {
    /// First delay, used again after [`Backoff::reset`].
    initial: Duration,
    /// Maximum delay.
    max: Duration,
    /// Delay returned by the next call to [`Backoff::next_delay`].
    next: Duration,
}

impl Backoff {
    /// Creates a backoff that starts at `initial` and never exceeds `max`.
    pub fn new(initial: Duration, max: Duration) -> Self {
        Self { initial, max, next: initial }
    }

    /// Returns the delay that [`Backoff::next_delay`] will return, without advancing.
    pub fn peek(&self) -> Duration {
        self.next
    }

    /// Returns the current delay and doubles it for the next call, up to the maximum.
    pub fn next_delay(&mut self) -> Duration {
        let delay = self.next;
        self.next = (self.next * 2).min(self.max);
        delay
    }

    /// Goes back to the initial delay, after a successful connection.
    pub fn reset(&mut self) {
        self.next = self.initial;
    }
}

#[cfg(test)]
mod tests {
    //! Delays of the backoff.

    use super::*;

    /// The delay doubles from 1 s up to the 5 s cap, and a reset starts over.
    #[test]
    fn backoff_doubles_up_to_the_cap_and_resets() {
        let mut backoff = Backoff::new(Duration::from_secs(1), Duration::from_secs(5));

        let delays: Vec<u64> = (0..5).map(|_| backoff.next_delay().as_secs()).collect();
        assert_eq!(delays, [1, 2, 4, 5, 5]);

        backoff.reset();
        assert_eq!(backoff.peek(), Duration::from_secs(1));
    }
}

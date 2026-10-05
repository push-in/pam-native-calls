<?php

declare(strict_types=1);

namespace Pam\Native\Calls;

/** User or system action on a call surface (notification, full-screen UI or timeout). */
enum CallActionKind: int
{
    case Accept = 1;
    case Decline = 2;
    case Open = 3;
    case HangUp = 4;
    case Timeout = 5;
}

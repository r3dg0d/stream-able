/*
 * Stream-able browser input compatibility shim.
 *
 * Why this exists
 * ---------------
 * MCEF/JCEF issue #4: in an off-screen (OSR) browser, a key press reaches Blink
 * as KEYEVENT_RAWKEYDOWN only. Blink resolves the *editing* commands for
 * Backspace and Enter from a key-down that the OSR path does not always deliver,
 * so a user can type text into a field but cannot delete or submit.
 *
 * Stream-able's first line of defence is native: it also sends the character
 * event that a real keyboard would produce (0x08 for Backspace, 0x0D for Enter),
 * which is what the platform does and what Blink expects. See
 * dev.streamable.browser.input.BrowserKeyboardCompat.
 *
 * This shim is the *fallback* for cases where that is still not enough. Its
 * defining property is that it is self-verifying: it lets the browser's own
 * default action run first, and only performs the edit itself when the DOM
 * verifiably did not change. Consequences:
 *
 *   - It can never double-delete or double-submit.
 *   - It only ever touches the focused, editable element.
 *   - It goes dormant automatically if MCEF/JCEF fixes the underlying bug,
 *     with no code change and no configuration.
 *
 * It deliberately does NOT blanket-run document.execCommand('delete') on every
 * Backspace, which would corrupt input on pages where the native path works.
 */
(function () {
    'use strict';
    if (window.__streamableInputShim) {
        return;
    }
    window.__streamableInputShim = { version: 1, repairs: 0, nativeOk: 0 };

    var WATCHED = { Backspace: 1, Delete: 1, Enter: 1 };

    function isTextField(el) {
        if (!el) return false;
        var tag = el.tagName;
        if (tag === 'TEXTAREA') return !el.disabled && !el.readOnly;
        if (tag !== 'INPUT') return false;
        var type = (el.type || 'text').toLowerCase();
        var typed = /^(text|search|url|tel|password|email|number)$/.test(type);
        return typed && !el.disabled && !el.readOnly;
    }

    function isContentEditable(el) {
        return !!el && el.isContentEditable === true;
    }

    function editableTarget() {
        var el = document.activeElement;
        if (isTextField(el) || isContentEditable(el)) return el;
        return null;
    }

    /* A cheap fingerprint used to detect whether the native default did anything. */
    function snapshot(el) {
        if (isTextField(el)) {
            return { kind: 'field', value: el.value, start: el.selectionStart, end: el.selectionEnd };
        }
        var sel = window.getSelection();
        return {
            kind: 'ce',
            html: el.innerHTML,
            offset: sel && sel.rangeCount ? sel.getRangeAt(0).startOffset : -1
        };
    }

    function changed(el, before) {
        if (!el || !el.isConnected) return true;   // element went away: assume handled
        var now = snapshot(el);
        if (now.kind !== before.kind) return true;
        if (now.kind === 'field') {
            return now.value !== before.value || now.start !== before.start;
        }
        return now.html !== before.html || now.offset !== before.offset;
    }

    function fireInput(el, inputType, data) {
        try {
            el.dispatchEvent(new InputEvent('input', {
                bubbles: true, cancelable: false, inputType: inputType, data: data === undefined ? null : data
            }));
        } catch (e) {
            el.dispatchEvent(new Event('input', { bubbles: true }));
        }
    }

    /* Applies the edit to a plain <input>/<textarea> using selection semantics. */
    function repairField(el, key, shiftKey) {
        var start = el.selectionStart;
        var end = el.selectionEnd;
        var value = el.value;
        if (start === null || end === null) return false;

        if (key === 'Enter') {
            if (el.tagName !== 'TEXTAREA') {
                // Single-line field: Enter submits rather than inserting.
                var form = el.form;
                if (form) {
                    if (typeof form.requestSubmit === 'function') form.requestSubmit();
                    else form.submit();
                    return true;
                }
                return false;
            }
            el.value = value.slice(0, start) + '\n' + value.slice(end);
            el.selectionStart = el.selectionEnd = start + 1;
            fireInput(el, shiftKey ? 'insertLineBreak' : 'insertLineBreak', '\n');
            return true;
        }

        if (start !== end) {                       // a selection is always just removed
            el.value = value.slice(0, start) + value.slice(end);
            el.selectionStart = el.selectionEnd = start;
            fireInput(el, 'deleteContentBackward');
            return true;
        }
        if (key === 'Backspace') {
            if (start === 0) return false;
            el.value = value.slice(0, start - 1) + value.slice(start);
            el.selectionStart = el.selectionEnd = start - 1;
            fireInput(el, 'deleteContentBackward');
            return true;
        }
        if (key === 'Delete') {
            if (start >= value.length) return false;
            el.value = value.slice(0, start) + value.slice(start + 1);
            el.selectionStart = el.selectionEnd = start;
            fireInput(el, 'deleteContentForward');
            return true;
        }
        return false;
    }

    /* contenteditable: use the Selection API, falling back to execCommand. */
    function repairContentEditable(el, key, shiftKey) {
        var sel = window.getSelection();
        if (!sel || !sel.rangeCount) return false;
        var range = sel.getRangeAt(0);

        if (key === 'Enter') {
            var node = document.createTextNode('\n');
            range.deleteContents();
            range.insertNode(node);
            range.setStartAfter(node);
            range.collapse(true);
            sel.removeAllRanges();
            sel.addRange(range);
            fireInput(el, 'insertLineBreak', '\n');
            return true;
        }
        if (range.collapsed) {
            try {
                sel.modify('extend', key === 'Backspace' ? 'backward' : 'forward', 'character');
            } catch (e) {
                return document.execCommand(key === 'Backspace' ? 'delete' : 'forwardDelete', false, null);
            }
        }
        if (window.getSelection().isCollapsed) return false;
        window.getSelection().deleteFromDocument();
        fireInput(el, key === 'Backspace' ? 'deleteContentBackward' : 'deleteContentForward');
        return true;
    }

    document.addEventListener('keydown', function (ev) {
        if (!WATCHED[ev.key]) return;
        var el = editableTarget();
        if (!el) return;                            // never touch a non-editable page

        var before = snapshot(el);
        // Yield so the browser's own default action gets its chance first.
        window.setTimeout(function () {
            if (changed(el, before)) {
                window.__streamableInputShim.nativeOk++;
                return;                             // native path worked - stay out of the way
            }
            var repaired = isTextField(el)
                ? repairField(el, ev.key, ev.shiftKey)
                : repairContentEditable(el, ev.key, ev.shiftKey);
            if (repaired) {
                window.__streamableInputShim.repairs++;
            }
        }, 0);
    }, true);

    /*
     * Paste fallback. Stream-able pushes Minecraft's clipboard into
     * window.__streamableClipboard before forwarding Ctrl+V, so if Chromium's
     * own paste does not take effect we can still insert the text. Same
     * verify-first rule: only runs when the field did not change.
     */
    document.addEventListener('keydown', function (ev) {
        if (ev.key !== 'v' && ev.key !== 'V') return;
        if (!ev.ctrlKey && !ev.metaKey) return;
        var el = editableTarget();
        if (!el || !isTextField(el)) return;
        var text = window.__streamableClipboard;
        if (typeof text !== 'string' || text.length === 0) return;

        var before = snapshot(el);
        window.setTimeout(function () {
            if (changed(el, before)) return;
            var start = el.selectionStart, end = el.selectionEnd;
            el.value = el.value.slice(0, start) + text + el.value.slice(end);
            el.selectionStart = el.selectionEnd = start + text.length;
            fireInput(el, 'insertFromPaste', text);
            window.__streamableInputShim.repairs++;
        }, 0);
    }, true);
})();

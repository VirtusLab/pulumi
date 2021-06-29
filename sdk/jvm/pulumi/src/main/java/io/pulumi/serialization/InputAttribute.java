package io.pulumi.serialization;

public class InputAttribute {
        public String name;
        public boolean isRequired;
        public boolean json;

        public InputAttribute(String name, boolean required, boolean json) {
            this.name = name;
            this.isRequired = required;
            this.json = json;
        }
}
